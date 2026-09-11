package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * The outbox repeats a failed dispatch, which is what makes losing a
 * concurrency conflict survivable. A failure the BPMS answers the same way every time
 * gains nothing from that, so an adapter may say
 * ({@code MigratableProcessService#isPhaseTwoFailureRepeatable}) that repeating cannot
 * help - the entry is then blocked after the first attempt instead of after the
 * configured ones.
 * <p>
 * The test runs on a database of its own: it blocks an entry on purpose, and a blocked
 * entry stays in the store for operations to find.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PermanentPhaseTwoFailureTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addClass(TestMeterRegistryProducer.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:outbox-permanent-it;DB_CLOSE_DELAY=-1");

  private static final String COUNT_BLOCKED_ENTRIES_OF_AGGREGATE = "SELECT COUNT(*) FROM VANILLABP_PHASE_TWO_OUTBOX "
      + "WHERE AGGREGATE_ID = '%s' AND STATUS = 'BLOCKED'";

  private static final String ATTEMPTS_OF_AGGREGATE = "SELECT MAX(ATTEMPTS) FROM VANILLABP_PHASE_TWO_OUTBOX "
      + "WHERE AGGREGATE_ID = '%s'";

  private static final String COUNT_ENTRIES_OF_AGGREGATE = "SELECT COUNT(*) FROM VANILLABP_PHASE_TWO_OUTBOX "
      + "WHERE AGGREGATE_ID = '%s'";

  @Inject
  WorkflowService workflowService;

  @Inject
  RecordingPhaseTwoListener listener;

  @Inject
  UserTransaction userTransaction;

  @Inject
  DataSource dataSource;

  @Inject
  io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry;

  @BeforeEach
  public void resetListener() {

    listener.reset();

  }

  private long count(
      final String query) throws Exception {

    try (var connection = dataSource.getConnection(); var statement = connection
        .createStatement(); var resultSet = statement.executeQuery(query)) {
      resultSet.next();
      return resultSet.getLong(1);
    }

  }

  @Test
  @DisplayName("A failure repeating cannot fix blocks the entry after the first attempt")
  public void permanentFailureBlocksTheEntryImmediately() throws Exception {

    final var blockedEntriesCounted = blockedEntriesCounted();
    listener.failNextDispatchesPermanently(1);

    userTransaction.begin();
    final var attachedAggregate = workflowService.startWorkflow("permanent-failure-test");
    userTransaction.commit();

    listener.awaitInvocations(1, 30_000);

    final var deadline = System.currentTimeMillis() + 30_000;
    while (count(COUNT_BLOCKED_ENTRIES_OF_AGGREGATE.formatted(attachedAggregate.getId())) == 0) {
      assertTrue(System.currentTimeMillis() < deadline, "the entry was not blocked");
      Thread.sleep(50);
    }

    // exactly one attempt, and nothing retries a blocked entry
    assertEquals(1, count(ATTEMPTS_OF_AGGREGATE.formatted(attachedAggregate.getId())));
    Thread.sleep(1500);
    assertEquals(1, count(ATTEMPTS_OF_AGGREGATE.formatted(attachedAggregate.getId())));
    assertEquals(
        1,
        listener
            .getInvocations()
            .stream()
            .filter(attachedAggregate.getId()::equals)
            .count(),
        "the dispatch must not be repeated");

    assertEquals(
        blockedEntriesCounted + 1.0,
        blockedEntriesCounted(),
        "a blocked entry is counted, because the gauge of waiting entries falls at that moment");

  }

  /**
   * The counter of blocked entries, or zero while nothing was blocked yet and the meter
   * does not exist. Read as a difference because the sibling test below blocks an entry
   * too and the order of the two is nobody's promise.
   *
   * @return How many entries of the JDBC store were blocked for a permanent failure
   */
  private double blockedEntriesCounted() {

    final var counter = meterRegistry
        .find(io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.OUTBOX_BLOCKED)
        .tag(
            io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.TAG_STORE,
            "JdbcPhaseTwoOutbox")
        .tag(
            io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.TAG_OPERATION,
            io.vanillabp.integration.spi.PhaseOperation.START_WORKFLOW.name())
        .tag(
            io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.TAG_PERMANENT,
            "true")
        .counter();
    return counter == null
        ? 0.0
        : counter.count();

  }

  /**
   * A blocked entry used to hold its deduplication key, and the store refuses an
   * identical key for as long as an entry holds it - so the failed operation silenced
   * its own repetition, with an answer indistinguishable from a correct deduplication.
   * Blocking releases the key now, exactly as a dispatch releases it.
   */
  @Test
  @DisplayName("A blocked entry does not swallow the next attempt of the same operation")
  public void aBlockedEntryReleasesItsDeduplicationKey() throws Exception {

    listener.failNextDispatchesPermanently(1);

    userTransaction.begin();
    final var attachedAggregate = workflowService.startWorkflow("blocked-releases-key");
    userTransaction.commit();

    listener.awaitInvocations(1, 30_000);
    final var deadline = System.currentTimeMillis() + 30_000;
    while (count(COUNT_BLOCKED_ENTRIES_OF_AGGREGATE.formatted(attachedAggregate.getId())) == 0) {
      assertTrue(System.currentTimeMillis() < deadline, "the entry was not blocked");
      Thread.sleep(50);
    }

    // the same operation, planned again while the blocked row still sits there
    userTransaction.begin();
    workflowService.startWorkflowAgain(attachedAggregate);
    userTransaction.commit();

    // it reaches the BPMS: a second entry was written and dispatched, and the blocked
    // one stays where it is for whoever repairs it
    assertEquals(
        2,
        listener
            .awaitInvocations(2, 30_000)
            .stream()
            .filter(attachedAggregate.getId()::equals)
            .count(),
        "the repetition of a blocked operation was discarded");
    assertEquals(1, count(COUNT_BLOCKED_ENTRIES_OF_AGGREGATE.formatted(attachedAggregate.getId())));
    assertEquals(2, count(COUNT_ENTRIES_OF_AGGREGATE.formatted(attachedAggregate.getId())));

  }

  /**
   * The counter-check: a failure the adapter reports as repeatable is still repeated,
   * so the blocking above is the adapter's answer and not a change of the store's
   * behaviour.
   */
  @Test
  @DisplayName("A repeatable failure is still retried instead of being blocked")
  public void repeatableFailureIsRetried() throws Exception {

    listener.failNextDispatches(1);

    userTransaction.begin();
    final var attachedAggregate = workflowService.startWorkflow("repeatable-failure-test");
    userTransaction.commit();

    final var invocations = listener.awaitInvocations(2, 30_000);
    assertEquals(attachedAggregate.getId(), invocations.get(0));
    assertEquals(attachedAggregate.getId(), invocations.get(1));
    assertEquals(0, count(COUNT_BLOCKED_ENTRIES_OF_AGGREGATE.formatted(attachedAggregate.getId())));

  }

}
