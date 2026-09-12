package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.IMetricsTracker;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * What a quiet application costs on the gruelbox store, counted in connections rather than
 * measured in seconds. A flush is three database commands whether or not anything is
 * waiting, and an application sitting in a timer used to pay for them every ten seconds.
 * <p>
 * Connections and not statements, because a connection is the claim from below: none taken is
 * none used, whatever the code would have sent over it. The pool reports every one of them.
 * <p>
 * The claim under test is not that the poller sleeps, it is that the database is not touched
 * while nothing is due. Each test boots its own application on its own database, because what
 * it varies is the cap on the sleep.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class OutboxSleepsWhileNothingIsDueTest {

  // one database per test, for the reason OutboxRecoveryTest gives
  private static final String DATASOURCE_URL_PATTERN = "jdbc:h2:mem:outbox-sleeping-%s;DB_CLOSE_DELAY=-1";

  /**
   * How often a connection was taken out of the pool. Hikari reports it to whatever tracker it
   * is given, which is how this test watches the application's own pool rather than a pool of
   * its own.
   */
  private final java.util.concurrent.atomic.AtomicLong connectionsTaken = new java.util.concurrent.atomic.AtomicLong();

  /**
   * Starts counting what the application takes out of its pool.
   *
   * @param dataSource The application's data source
   */
  private void countConnectionsOf(
      final javax.sql.DataSource dataSource) {

    final IMetricsTracker tracker = new IMetricsTracker() {
      @Override
      public void recordConnectionAcquiredNanos(
          final long elapsedAcquiredNanos) {
        connectionsTaken.incrementAndGet();
      }
    };
    ((HikariDataSource) dataSource).setMetricsTrackerFactory((
        poolName,
        poolStats) -> tracker);

  }

  private ConfigurableApplicationContext runApplication(
      final String database,
      final String longestSleep) {

    return new SpringApplicationBuilder(TestApplication.class)
        .web(WebApplicationType.NONE)
        .run(
            "--spring.datasource.url="
                + DATASOURCE_URL_PATTERN.formatted(database),
            "--vanillabp.outbox.poll-interval="
                + longestSleep,
            "--vanillabp.outbox.attempt-frequency=PT0.5S");

  }

  private Aggregate startAWorkflow(
      final ConfigurableApplicationContext context,
      final String content) {

    @SuppressWarnings("unchecked")
    final var processService = (ProcessService<Aggregate>) context
        .getBeanProvider(ResolvableType.forClassWithGenerics(ProcessService.class, Aggregate.class))
        .getObject();
    final var transactionTemplate = context.getBean(TransactionTemplate.class);
    final var attached = transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent(content);
      return processService.startWorkflow(aggregate);
    });
    assertNotNull(attached);
    return attached;

  }

  /**
   * Waits until gruelbox has dispatched everything it holds, which is the state "nothing is
   * due" these tests start counting from.
   *
   * @param dataSource Where gruelbox' table lives
   */
  private void awaitNothingLeftUndone(
      final DataSource dataSource) throws Exception {

    final var deadline = System.currentTimeMillis() + 30000;
    while (count(
        dataSource,
        "SELECT COUNT(*) FROM TXNO_OUTBOX WHERE processed = false AND blocked = false") > 0) {
      assertTrue(System.currentTimeMillis() < deadline, "an entry of the outbox was never dispatched");
      Thread.sleep(50);
    }

  }

  private long count(
      final DataSource dataSource,
      final String query) throws Exception {

    try (var connection = dataSource.getConnection(); var statement = connection
        .createStatement(); var resultSet = statement.executeQuery(query)) {
      resultSet.next();
      return resultSet.getLong(1);
    }

  }

  @Test
  @DisplayName("No connection is taken while the store owes nothing")
  public void aQuietStoreIsAskedNothing() throws Exception {

    try (var context = runApplication("quiet", "PT1H")) {
      final var dataSource = context.getBean(DataSource.class);
      countConnectionsOf(dataSource);
      startAWorkflow(context, "quiet-store");
      awaitNothingLeftUndone(dataSource);
      // the zero below is a measurement rather than a silence only if the counter sees a
      // connection at all
      assertTrue(connectionsTaken.get() > 0, "the pool has to report what this test itself took");

      connectionsTaken.set(0);
      Thread.sleep(3000);

      assertEquals(
          0L,
          connectionsTaken.get(),
          "a store with nothing to do must not take a connection, and a connection is what every "
              + "statement needs");
    }

  }

  @Test
  @DisplayName("A blocked entry does not keep the poller awake")
  public void aBlockedEntryIsNotSomethingToWakeUpFor() throws Exception {

    try (var context = runApplication("blocked", "PT1H")) {
      final var dataSource = context.getBean(DataSource.class);
      final var listener = context.getBean(RecordingPhaseTwoListener.class);
      countConnectionsOf(dataSource);
      // a failure the adapter calls permanent is blocked on the first attempt, which is
      // the shortest way to an entry waiting for a person rather than for a clock
      listener.failNextDispatchesPermanently(Integer.MAX_VALUE);
      startAWorkflow(context, "blocked-entry");

      final var deadline = System.currentTimeMillis() + 30000;
      while (count(dataSource, "SELECT COUNT(*) FROM TXNO_OUTBOX WHERE blocked = true") == 0) {
        assertTrue(System.currentTimeMillis() < deadline, "the entry was never blocked");
        Thread.sleep(50);
      }
      assertTrue(connectionsTaken.get() > 0, "the pool has to report what this test itself took");

      connectionsTaken.set(0);
      Thread.sleep(3000);

      assertEquals(
          0L,
          connectionsTaken.get(),
          "an entry nobody can dispatch until it is repaired must not be asked about");
    }

  }

  @Test
  @DisplayName("An entry another node left behind is dispatched within the cap")
  public void theCapPicksUpWhatAnotherNodeLeftBehind() throws Exception {

    // nothing notifies a sleeping node about an entry another node wrote, and where no
    // shared cache reports that node going away the cap is what covers it
    try (var context = runApplication("cap", "PT2S")) {
      final var dataSource = context.getBean(DataSource.class);
      final var listener = context.getBean(RecordingPhaseTwoListener.class);
      startAWorkflow(context, "capped-sleep");
      awaitNothingLeftUndone(dataSource);
      assertEquals(1, listener.getInvocations().size());

      // the row is opened again the way the wiki tells an operator to open one, which is
      // also what another node inserting an entry looks like from here: a due row nothing
      // told this node about
      try (var connection = dataSource.getConnection(); var statement = connection
          .createStatement()) {
        statement.executeUpdate(
            """
                UPDATE TXNO_OUTBOX SET processed = false, attempts = 0, \
                nextAttemptTime = DATEADD('SECOND', -60, CURRENT_TIMESTAMP)""");
      }

      final var deadline = System.currentTimeMillis() + 30000;
      while (listener.getInvocations().size() < 2) {
        assertTrue(
            System.currentTimeMillis() < deadline,
            "the reopened entry was not dispatched within the cap");
        Thread.sleep(50);
      }
    }

  }

}
