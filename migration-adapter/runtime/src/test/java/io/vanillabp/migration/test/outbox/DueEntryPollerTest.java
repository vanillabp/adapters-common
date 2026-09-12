package io.vanillabp.migration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.outbox.DueEntryPoller;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The schedule every outbox store shares: it asks when the store owes something and sleeps
 * until then, which is what lets an application with nothing to do leave its database
 * alone.
 * <p>
 * The four stores differ in how they read that moment and not in what happens to it, so the
 * rules are pinned here, in milliseconds, instead of four times over a database. What each
 * store does cost a quiet application is counted in statements by the integration test of
 * that store.
 */
@ExtendWith(SuppressOutputExtension.class)
public class DueEntryPollerTest {

  private DueEntryPoller poller;

  private final CopyOnWriteArrayList<Instant> polls = new CopyOnWriteArrayList<>();

  /**
   * What the store answers when it is asked for its earliest unfinished entry.
   */
  private final AtomicReference<Instant> earliestDueAt = new AtomicReference<>();

  @AfterEach
  public void stopThePoller() {

    if (poller != null) {
      poller.stop();
      poller = null;
    }

  }

  private DueEntryPoller aPollerSleepingAtMost(
      final Duration longestSleep) {

    poller = new DueEntryPoller(
        "vanillabp-outbox-test", longestSleep, () -> polls.add(Instant.now()), earliestDueAt::get);
    return poller;

  }

  private void awaitPolls(
      final int count) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 5000;
    while (polls.size() < count) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "expected at least %d poll(s) but got %d".formatted(count, polls.size()));
      Thread.sleep(10);
    }

  }

  @Test
  @DisplayName("A store which owes nothing is asked again after the cap and not before")
  public void aStoreWhichOwesNothingIsLeftAloneUntilTheCap() throws Exception {

    // nothing is owed, so the only thing which brings the poller back is the cap - which
    // is there for work a node wrote down before it went away
    earliestDueAt.set(null);
    aPollerSleepingAtMost(Duration.ofSeconds(30)).start();

    awaitPolls(1);
    Thread.sleep(500);
    assertEquals(1, polls.size(), "a poller with nothing to do must not come back within the cap");

  }

  @Test
  @DisplayName("The poller comes back when the store says its entry is due")
  public void theNextPollHappensWhenTheEntryIsDue() throws Exception {

    // an hour of cap, so a second poll can only come from the due time below
    earliestDueAt.set(Instant.now().plusMillis(300));
    aPollerSleepingAtMost(Duration.ofHours(1)).start();

    awaitPolls(2);
    final var slept = Duration.between(polls.get(0), polls.get(1));
    assertTrue(
        slept.toMillis() >= 200,
        "the poller came back after %s, which is sooner than the entry was due".formatted(slept));

  }

  @Test
  @DisplayName("An entry due now pulls an hour of sleep forward")
  public void anEntryDueNowShortensTheSleep() throws Exception {

    earliestDueAt.set(null);
    aPollerSleepingAtMost(Duration.ofHours(1)).start();
    awaitPolls(1);

    poller.somethingIsDueAt(Instant.now());

    awaitPolls(2);

  }

  @Test
  @DisplayName("An entry due in an hour pulls nothing forward")
  public void anEntryDueMuchLaterLeavesTheSleepAlone() throws Exception {

    earliestDueAt.set(null);
    aPollerSleepingAtMost(Duration.ofHours(1)).start();
    awaitPolls(1);

    poller.somethingIsDueAt(Instant.now().plus(Duration.ofHours(1)));

    Thread.sleep(500);
    assertEquals(1, polls.size(), "a notification about an entry due later must not wake anybody");

  }

  @Test
  @DisplayName("A store answering 'due now' forever is not asked as fast as the thread can ask")
  public void aStoreAnsweringDueNowForeverIsNotSpunOn() throws Exception {

    // two nodes racing for the same entry can answer this for a moment, and a poller
    // without a floor would turn that moment into a busy loop
    earliestDueAt.set(Instant.now().minus(Duration.ofHours(1)));
    aPollerSleepingAtMost(Duration.ofHours(1)).start();

    awaitPolls(2);
    Thread.sleep(300);
    assertTrue(
        polls.size() < 20,
        "the poller ran %d times in 300ms, which is a spin rather than a schedule".formatted(polls.size()));

  }

  @Test
  @DisplayName("A store which cannot answer is asked again after the cap")
  public void aStoreWhichThrowsIsAskedAgainAfterTheCap() throws Exception {

    poller = new DueEntryPoller(
        "vanillabp-outbox-test", Duration.ofMillis(300), () -> polls.add(Instant.now()), () -> {
          throw new IllegalStateException("the store cannot be reached");
        });
    poller.start();

    // the cap is what keeps a store with a broken connection being looked at, and the
    // poll itself is where that failure is reported
    awaitPolls(3);

  }

  @Test
  @DisplayName("Stopping and starting twice are each a no-op")
  public void startingAndStoppingTwiceChangeNothing() throws Exception {

    earliestDueAt.set(null);
    aPollerSleepingAtMost(Duration.ofHours(1)).start();
    poller.start();
    awaitPolls(1);

    poller.stop();
    poller.stop();

    poller.somethingIsDueAt(Instant.now());
    Thread.sleep(300);
    assertEquals(1, polls.size(), "a stopped poller must not be woken by a notification");

  }

}
