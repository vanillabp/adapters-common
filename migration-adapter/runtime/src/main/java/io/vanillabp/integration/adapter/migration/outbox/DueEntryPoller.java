package io.vanillabp.integration.adapter.migration.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;

/**
 * The clock of an outbox dispatcher: it runs the dispatcher's poll, then asks the store
 * when its earliest unfinished entry is due and sleeps until exactly that moment. A store
 * which owes nothing is left alone until the cap below elapses, so an application waiting
 * in a timer issues no database command at all.
 * <p>
 * Shared by the four stores VanillaBP ships on both platforms, because they differ in HOW
 * they read the next due time and not in what they do with it. Each one keeps its own
 * private single-thread daemon executor: no framework scheduler is registered or used, so
 * an application's own scheduling setup stays as it is.
 * <p>
 * A commit which planned an operation calls {@link #somethingIsDueAt(Instant)}, and that
 * is the fast path rather than an optimization: it pulls the sleep forward to the moment
 * named and leaves a sleep which already ends earlier alone. An entry due in three seconds
 * therefore shortens an hour of sleep, and an entry due in an hour does not shorten
 * anything.
 * <p>
 * Why nothing wakes the OTHER nodes of a cluster, and what the cap is for, is decision 41
 * in the repository's DECISIONS.md.
 */
@Slf4j
public class DueEntryPoller {

  /**
   * The shortest distance between two polls. It guards against a spin: a store answering
   * "due now" to every question - two nodes racing for the same entry is the case which
   * gets close to it - would otherwise be asked as fast as the thread can ask.
   */
  private static final Duration SHORTEST_SLEEP = Duration.ofMillis(50);

  private final String threadName;

  private final Runnable poll;

  /**
   * When the store's earliest unfinished entry is due, or <code>null</code> where the
   * store owes nothing.
   */
  private final Supplier<Instant> earliestDueAt;

  private final Duration longestSleep;

  private ScheduledExecutorService executor;

  private ScheduledFuture<?> nextPoll;

  /**
   * The moment {@link #nextPoll} is going to run at, which is what tells a notification
   * whether it has anything to pull forward.
   */
  private Instant nextPollAt;

  /**
   * @param threadName The name of the daemon thread polling, which is what an operator
   *          reads in a thread dump
   * @param longestSleep How long the poller sleeps while the store owes nothing
   * @param poll Dispatches what is due in one store, exceptions included
   * @param earliestDueAt Reads when the store's earliest unfinished entry is due
   */
  public DueEntryPoller(
      final String threadName,
      final Duration longestSleep,
      final Runnable poll,
      final Supplier<Instant> earliestDueAt) {

    this.threadName = threadName;
    this.longestSleep = longestSleep;
    this.poll = poll;
    this.earliestDueAt = earliestDueAt;

  }

  /**
   * Starts polling. The first poll runs at once, which is what dispatches the entries a
   * previously crashed instance left behind. Starting twice is a no-op.
   */
  public synchronized void start() {

    if (executor != null) {
      return;
    }
    executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
      final var thread = new Thread(runnable, threadName);
      thread.setDaemon(true);
      return thread;
    });
    pollAt(Instant.now());

  }

  /**
   * Stops polling on shutdown of the application. Stopping twice is a no-op.
   */
  public synchronized void stop() {

    if (executor == null) {
      return;
    }
    executor.shutdownNow();
    executor = null;
    nextPoll = null;
    nextPollAt = null;

  }

  /**
   * Pulls the next poll forward to the given moment, or leaves it where it is when it
   * already ends earlier. Called after a commit which planned an operation, with the
   * moment that operation is due.
   *
   * @param dueAt When the planned entry wants to be dispatched
   */
  public synchronized void somethingIsDueAt(
      final Instant dueAt) {

    pollAt(dueAt);

  }

  private synchronized void pollAt(
      final Instant moment) {

    if (executor == null) {
      return;
    }
    if ((nextPoll != null) && !nextPoll.isDone()) {
      if ((nextPollAt != null) && !moment.isBefore(nextPollAt)) {
        return;
      }
      nextPoll.cancel(false);
    }
    nextPollAt = moment;
    final var sleep = Math.max(0L, Duration.between(Instant.now(), moment).toMillis());
    nextPoll = executor.schedule(this::pollAndSleepUntilTheNextEntryIsDue, sleep, TimeUnit.MILLISECONDS);

  }

  private void pollAndSleepUntilTheNextEntryIsDue() {

    // the poll about to run is no longer a wake-up which could be pulled forward, and
    // saying so is what lets a notification arriving DURING this poll schedule the next one:
    // that entry may have been written after this poll read its due rows, so it needs a turn
    // of its own rather than the long sleep computed below
    forgetTheScheduledPoll();
    try {
      poll.run();
    } catch (final RuntimeException | Error e) {
      // a scheduled task which lets anything escape is never run again, and the executor
      // keeps the reason to itself: the entries of this store would stop being dispatched
      // and nothing would say so. Each store catches what its own poll throws, so reaching
      // this means the catching has a hole
      log.error("Polling the VanillaBP phase-two outbox threw where nothing should - will poll again", e);
    } finally {
      scheduleTheNextPoll();
    }

  }

  /**
   * Schedules the next poll, whatever happened in this one. An exception on the way to the
   * next wake-up would end the only thread which could ever dispatch this store's entries,
   * and the executor would keep the reason to itself.
   */
  private void scheduleTheNextPoll() {

    try {
      pollAt(whenTheNextEntryIsDue());
    } catch (final RuntimeException | Error e) {
      log.error("Could not schedule the next poll of the VanillaBP phase-two outbox - retrying at the cap", e);
      try {
        pollAt(Instant.now().plus(longestSleep));
      } catch (final RuntimeException | Error ignored) {
        log.error("The VanillaBP phase-two outbox is not polled any more", ignored);
      }
    }

  }

  private synchronized void forgetTheScheduledPoll() {

    nextPoll = null;
    nextPollAt = null;

  }

  /**
   * When to look again: the due time of the store's earliest unfinished entry, bounded
   * below so a store answering "due now" forever cannot turn into a spin, and bounded
   * above by the cap an operator configured.
   *
   * @return The moment to poll at
   */
  private Instant whenTheNextEntryIsDue() {

    final var now = Instant.now();
    final var cap = now.plus(longestSleep);
    final Instant earliest;
    try {
      earliest = earliestDueAt.get();
    } catch (final RuntimeException e) {
      // a store which cannot answer has a problem the poll itself will report as well,
      // so this falls back to the cap instead of ending the only thread which could
      // recover from it
      log.warn("Could not read when the next phase-two outbox entry is due - looking again in {}", longestSleep, e);
      return cap;
    }
    if ((earliest == null) || earliest.isAfter(cap)) {
      return cap;
    }
    final var floor = now.plus(SHORTEST_SLEEP);
    return earliest.isBefore(floor) ? floor : earliest;

  }

}
