package io.vanillabp.migration.test.observability;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vanillabp.integration.adapter.migration.observability.MicrometerVanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The Micrometer side of what the core records: the meters carry the tags
 * a deployment fixes and nothing which grows with the number of workflows, they
 * survive a registry which arrives late, and recording before any registry exists
 * costs nothing.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MicrometerVanillaBpMetricsTest {

  @Test
  @DisplayName("Nothing is recorded before a registry was bound")
  public void recordsAreDroppedWithoutARegistry() {

    final var metrics = new MicrometerVanillaBpMetrics();

    Assertions
        .assertDoesNotThrow(
            () -> {
              metrics
                  .taskDelivered(
                      "c8", "module", "Process", "task", VanillaBpMetrics.DeliveryOutcome.COMPLETED, 1_000L);
              metrics.outboxDispatchStarted("START_WORKFLOW", true);
              metrics.outboxDispatchFailed("START_WORKFLOW", false);
              metrics.outboxScheduleDiscarded("START_WORKFLOW");
              metrics.taskRedeliveryDeduplicated("c8", "module", "Process", "task");
            },
            "beans are built before the metrics infrastructure binds them");

    final var registry = new SimpleMeterRegistry();
    metrics.bindTo(registry);
    Assertions
        .assertTrue(
            registry
                .getMeters()
                .isEmpty(),
            "what happened before the binding is gone, not counted twice");

  }

  @Test
  @DisplayName("A delivery is counted by outcome and measured, tagged by where it happened")
  public void deliveriesAreCountedAndMeasured() {

    final var registry = new SimpleMeterRegistry();
    final var metrics = new MicrometerVanillaBpMetrics();
    metrics.bindTo(registry);

    metrics
        .taskDelivered("c8", "module", "Process", "task", VanillaBpMetrics.DeliveryOutcome.COMPLETED, 5_000_000L);
    metrics
        .taskDelivered("c8", "module", "Process", "task", VanillaBpMetrics.DeliveryOutcome.COMPLETED, 7_000_000L);
    metrics
        .taskDelivered("c8", "module", "Process", "task", VanillaBpMetrics.DeliveryOutcome.FAILED, 1_000_000L);

    Assertions
        .assertEquals(
            2.0,
            registry
                .get(VanillaBpMetrics.TASK_DELIVERIES)
                .tag(VanillaBpMetrics.TAG_OUTCOME, "completed")
                .counter()
                .count());
    Assertions
        .assertEquals(
            1.0,
            registry
                .get(VanillaBpMetrics.TASK_DELIVERIES)
                .tag(VanillaBpMetrics.TAG_OUTCOME, "failed")
                .counter()
                .count());

    final var timer = registry
        .get(VanillaBpMetrics.TASK_DELIVERY_DURATION)
        .tag(VanillaBpMetrics.TAG_ADAPTER, "c8")
        .tag(VanillaBpMetrics.TAG_WORKFLOW_MODULE, "module")
        .tag(VanillaBpMetrics.TAG_BPMN_PROCESS, "Process")
        .tag(VanillaBpMetrics.TAG_TASK_DEFINITION, "task")
        .timer();
    Assertions
        .assertEquals(
            3L,
            timer.count(),
            "the timer measures the delivery, whatever it ended with");
    Assertions.assertEquals(13.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.001);

  }

  @Test
  @DisplayName("A value nobody supplied is named rather than left empty")
  public void missingTagValuesAreNamed() {

    final var registry = new SimpleMeterRegistry();
    final var metrics = new MicrometerVanillaBpMetrics();
    metrics.bindTo(registry);

    metrics.taskDelivered(null, "module", "Process", "  ", VanillaBpMetrics.DeliveryOutcome.PENDING, 1L);

    Assertions
        .assertEquals(
            1.0,
            registry
                .get(VanillaBpMetrics.TASK_DELIVERIES)
                .tag(VanillaBpMetrics.TAG_ADAPTER, VanillaBpMetrics.TAG_VALUE_UNKNOWN)
                .tag(VanillaBpMetrics.TAG_TASK_DEFINITION, VanillaBpMetrics.TAG_VALUE_UNKNOWN)
                .counter()
                .count());

  }

  @Test
  @DisplayName("The outbox counts its dispatches, its retries and its failures")
  public void outboxDispatchesAreCounted() {

    final var registry = new SimpleMeterRegistry();
    final var metrics = new MicrometerVanillaBpMetrics();
    metrics.bindTo(registry);

    metrics.outboxDispatchStarted("START_WORKFLOW", false);
    metrics.outboxDispatchStarted("START_WORKFLOW", true);
    metrics.outboxDispatchFailed("START_WORKFLOW", false);
    metrics.outboxDispatchFailed("START_WORKFLOW", true);

    Assertions
        .assertEquals(
            2.0,
            registry
                .get(VanillaBpMetrics.OUTBOX_DISPATCHES)
                .tag(VanillaBpMetrics.TAG_OPERATION, "START_WORKFLOW")
                .counter()
                .count());
    Assertions
        .assertEquals(
            1.0,
            registry
                .get(VanillaBpMetrics.OUTBOX_RETRIES)
                .counter()
                .count(),
            "only the dispatch of an entry which was attempted before is a retry");
    Assertions
        .assertEquals(
            1.0,
            registry
                .get(VanillaBpMetrics.OUTBOX_FAILURES)
                .tag(VanillaBpMetrics.TAG_PERMANENT, "true")
                .counter()
                .count(),
            "a failure repeating cannot fix is told apart from one which can");

  }

  @Test
  @DisplayName("A blocked entry is counted per store, because the backlog gauge falls when one is")
  public void blockedOutboxEntriesAreCounted() {

    final var registry = new SimpleMeterRegistry();
    final var metrics = new MicrometerVanillaBpMetrics();
    metrics.bindTo(registry);

    metrics.outboxEntryBlocked("JdbcPhaseTwoOutbox", "START_WORKFLOW", true);
    metrics.outboxEntryBlocked("JdbcPhaseTwoOutbox", "START_WORKFLOW", false);
    metrics.outboxEntryBlocked("GruelboxPhaseTwoOutbox", "START_WORKFLOW", true);

    Assertions
        .assertEquals(
            1.0,
            registry
                .get(VanillaBpMetrics.OUTBOX_BLOCKED)
                .tag(VanillaBpMetrics.TAG_STORE, "JdbcPhaseTwoOutbox")
                .tag(VanillaBpMetrics.TAG_PERMANENT, "true")
                .counter()
                .count(),
            "an entry blocked on the adapter's verdict is told apart from one whose attempts ran out");
    Assertions
        .assertEquals(
            1.0,
            registry
                .get(VanillaBpMetrics.OUTBOX_BLOCKED)
                .tag(VanillaBpMetrics.TAG_STORE, "GruelboxPhaseTwoOutbox")
                .tag(VanillaBpMetrics.TAG_PERMANENT, "true")
                .counter()
                .count(),
            "an application running two stores sees which of them lost the operation");

  }

  @Test
  @DisplayName("A refused schedule is counted per operation")
  public void discardedSchedulesAreCounted() {

    final var registry = new SimpleMeterRegistry();
    final var metrics = new MicrometerVanillaBpMetrics();
    metrics.bindTo(registry);

    metrics.outboxScheduleDiscarded("CORRELATE_MESSAGE");
    metrics.outboxScheduleDiscarded("CORRELATE_MESSAGE");
    metrics.outboxScheduleDiscarded("START_WORKFLOW");

    Assertions
        .assertEquals(
            2.0,
            registry
                .get(VanillaBpMetrics.OUTBOX_DISCARDED)
                .tag(VanillaBpMetrics.TAG_OPERATION, "CORRELATE_MESSAGE")
                .counter()
                .count());
    Assertions
        .assertEquals(
            1.0,
            registry
                .get(VanillaBpMetrics.OUTBOX_DISCARDED)
                .tag(VanillaBpMetrics.TAG_OPERATION, "START_WORKFLOW")
                .counter()
                .count(),
            "a lost message and a workflow which never started are different alarms");

  }

  @Test
  @DisplayName("An election answered by the delivery record is counted per operation")
  public void electionsAnsweredFromTheRecordAreCounted() {

    final var registry = new SimpleMeterRegistry();
    final var metrics = new MicrometerVanillaBpMetrics();
    metrics.bindTo(registry);

    metrics.taskElectionAnsweredFromRecord("c8", "module", "Process", "COMPLETE_TASK");
    metrics.taskElectionAnsweredFromRecord("c8", "module", "Process", "COMPLETE_TASK");
    metrics.taskElectionAnsweredFromRecord("c8", "module", "Process", "AGGREGATE_CHANGED");

    Assertions
        .assertEquals(
            2.0,
            registry
                .get(VanillaBpMetrics.TASK_ELECTIONS_FROM_RECORD)
                .tag(VanillaBpMetrics.TAG_ADAPTER, "c8")
                .tag(VanillaBpMetrics.TAG_WORKFLOW_MODULE, "module")
                .tag(VanillaBpMetrics.TAG_BPMN_PROCESS, "Process")
                .tag(VanillaBpMetrics.TAG_OPERATION, "COMPLETE_TASK")
                .counter()
                .count(),
            "what the record saved is read per operation, and the place it happened is a tag");
    Assertions
        .assertEquals(
            1.0,
            registry
                .get(VanillaBpMetrics.TASK_ELECTIONS_FROM_RECORD)
                .tag(VanillaBpMetrics.TAG_OPERATION, "AGGREGATE_CHANGED")
                .counter()
                .count());

  }

  @Test
  @DisplayName("A store which can count its waiting entries gets a gauge, whenever it registers")
  public void pendingOutboxEntriesAreGauged() {

    final var pending = new java.util.concurrent.atomic.AtomicLong(3);
    // no holding period, so the test sees what the store says right now
    final var metrics = new MicrometerVanillaBpMetrics(java.time.Duration.ZERO);

    // registered BEFORE the registry exists - which is what a startup does
    metrics
        .registerPendingOutboxEntries(
            "JdbcPhaseTwoOutbox",
            () -> java.util.OptionalLong.of(pending.get()));

    final var registry = new SimpleMeterRegistry();
    metrics.bindTo(registry);

    Assertions
        .assertEquals(
            3.0,
            registry
                .get(VanillaBpMetrics.OUTBOX_PENDING)
                .tag(VanillaBpMetrics.TAG_STORE, "JdbcPhaseTwoOutbox")
                .gauge()
                .value());

    pending.set(11);
    Assertions
        .assertEquals(
            11.0,
            registry
                .get(VanillaBpMetrics.OUTBOX_PENDING)
                .gauge()
                .value(),
            "a gauge reports what the store says now, not what it said at startup");

  }

  @Test
  @DisplayName("Reading the pending gauge does not query the store on every collection")
  public void thePendingGaugeIsHeldBetweenCollections() {

    final var queries = new java.util.concurrent.atomic.AtomicInteger();
    final var metrics = new MicrometerVanillaBpMetrics(java.time.Duration.ofMinutes(5));
    metrics
        .registerPendingOutboxEntries(
            "JdbcPhaseTwoOutbox",
            () -> java.util.OptionalLong.of(queries.incrementAndGet()));

    final var registry = new SimpleMeterRegistry();
    metrics.bindTo(registry);
    final var gauge = registry
        .get(VanillaBpMetrics.OUTBOX_PENDING)
        .gauge();

    Assertions.assertEquals(1.0, gauge.value());
    Assertions.assertEquals(1.0, gauge.value());
    Assertions.assertEquals(1.0, gauge.value());

    Assertions
        .assertEquals(
            1,
            queries.get(),
            "a dashboard next to a scrape must not turn watching the outbox into load on it");

  }

  @Test
  @DisplayName("A store which cannot say leaves a gap rather than reporting zero")
  public void anUnavailableStoreReportsNoMeasurement() {

    final var metrics = new MicrometerVanillaBpMetrics(java.time.Duration.ZERO);
    metrics.registerPendingOutboxEntries("JdbcPhaseTwoOutbox", java.util.OptionalLong::empty);

    final var registry = new SimpleMeterRegistry();
    metrics.bindTo(registry);

    Assertions
        .assertTrue(
            Double
                .isNaN(registry
                    .get(VanillaBpMetrics.OUTBOX_PENDING)
                    .gauge()
                    .value()),
            "a zero would be a claim nobody checked");

  }

}
