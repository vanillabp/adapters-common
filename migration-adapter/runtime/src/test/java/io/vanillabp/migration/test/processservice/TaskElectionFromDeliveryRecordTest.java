package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.processservice.DeliveryRecords;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.migration.test.RecordedPhaseOperations;

/**
 * Completing a task asks the delivery record instead of the BPMS.
 * <p>
 * VanillaBP wrote that record while the handler of the task ran, in the database of the
 * workflow aggregate, and it names the adapter which delivered the task. So the walk over
 * the adapters - one command against a remote BPMS per operation, and on Camunda 8 the very
 * command the adapter's own phase one sends a moment later - is not needed while the record
 * answers. What it must NOT do is answer where it cannot: no record, an adapter which is not
 * prioritized any more, a store which cannot be queried by a task, and the probe decides as
 * it always did.
 * <p>
 * The same holds for a call which names a task although its operation is about the workflow -
 * pushing a changed aggregate into the scope of one task. It asks about that task like every
 * other call naming one, as long as the task is open; a task which is over says nothing about
 * the workflow around it. And how often the record answered is counted, because that number is
 * what says whether reading it was worth anything.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TaskElectionFromDeliveryRecordTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  /**
   * A second process of the same workflow service, called by the primary one. Its
   * deliveries reach the instance of THIS id, so what they write down carries it, while
   * the application calls its operations on the primary service.
   */
  private static final String SECONDARY_PROCESS = "CalledProcess";

  private static final String ADAPTER = "c8";

  private static final String AGGREGATE = "4711";

  private static final String TASK = "job-1";

  /**
   * An adapter which counts every question the election puts to it, and would answer that
   * it holds the task - so a test asserting that nothing was asked really asserts the
   * saving and not a failure.
   */
  static class ProbeAdapter implements MigratableProcessService<Object> {

    final AtomicInteger probes = new AtomicInteger();

    final RecordedPhaseOperations<Object> operations = new RecordedPhaseOperations<>();

    private final String adapterId;

    ProbeAdapter(
        final String adapterId) {

      this.adapterId = adapterId;

    }

    @Override
    public String getAdapterId() {

      return adapterId;

    }

    @Override
    public Map<PhaseOperation, PhaseOperationHandler<Object>> phaseOperations() {

      return operations.operations();

    }

    @Override
    public WorkflowAwareness awarenessOfTask(
        final WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId) {

      probes.incrementAndGet();
      return WorkflowAwareness.ACTIVE;

    }

    @Override
    public WorkflowAwareness awarenessOfUserTask(
        final WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId) {

      probes.incrementAndGet();
      return WorkflowAwareness.ACTIVE;

    }

    @Override
    public WorkflowAwareness awarenessOfWorkflow(
        final WorkflowScope scope,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId) {

      probes.incrementAndGet();
      return WorkflowAwareness.ACTIVE;

    }

    @Override
    public boolean deliversTasksAtLeastOnce() {

      return true;

    }

  }

  /**
   * A delivery log in memory which can be queried by the task, exactly like the stores
   * VanillaBP ships.
   */
  static class InMemoryDeliveryLog implements TaskDeliveryLog {

    final Map<String, TaskDelivery> records = new HashMap<>();

    @Override
    public Optional<TaskDelivery> recordedDelivery(
        final String deliveryKey) {

      return Optional.ofNullable(records.get(deliveryKey));

    }

    @Override
    public boolean record(
        final TaskDelivery delivery) {

      return records.putIfAbsent(delivery.deliveryKey(), delivery) == null;

    }

    @Override
    public Optional<TaskDelivery> recordOfTask(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId,
        final String taskId) {

      return records
          .values()
          .stream()
          .filter(record -> workflowModuleId.equals(record.workflowModuleId()))
          .filter(record -> bpmnProcessId.equals(record.bpmnProcessId()))
          .filter(record -> workflowAggregateId.equals(record.workflowAggregateId()))
          .filter(record -> taskId.equals(record.taskId()))
          .filter(record -> "COMPLETION_PENDING".equals(record.outcome()))
          .findFirst();

    }

    @Override
    public int markTaskClosed(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId,
        final String taskId) {

      final var open = recordOfTask(workflowModuleId, bpmnProcessId, workflowAggregateId, taskId)
          .filter(record -> record.taskClosedAt() == null)
          .orElse(null);
      if (open == null) {
        return 0;
      }
      records
          .put(
              open.deliveryKey(),
              new TaskDelivery(
                  open.deliveryKey(), open.adapterId(), open.workflowModuleId(), open
                      .bpmnProcessId(), open.workflowAggregateId(), open.taskDefinition(), open
                          .taskId(), open.outcome(), open.bpmnErrorCode(), open
                              .bpmnErrorName(), open.recordedAt(), Instant.now()));
      return 1;

    }

  }

  /**
   * A store which cannot be queried by a task - every log written before this existed, and
   * the shape the fallback has to survive.
   */
  static class LogWithoutTaskLookup implements TaskDeliveryLog {

    @Override
    public Optional<TaskDelivery> recordedDelivery(
        final String deliveryKey) {

      return Optional.empty();

    }

    @Override
    public boolean record(
        final TaskDelivery delivery) {

      return true;

    }

  }

  /** What the elections answered from the record were counted as. */
  static class RecordingMetrics implements VanillaBpMetrics {

    /**
     * One counted election.
     *
     * @param adapterId The adapter the record named
     * @param workflowModuleId The workflow module of the BPMN process
     * @param bpmnProcessId The BPMN process the task belongs to
     * @param operation The operation whose call named the task
     */
    record AnsweredElection(
                            String adapterId,
                            String workflowModuleId,
                            String bpmnProcessId,
                            String operation) {
    }

    final List<AnsweredElection> answeredFromRecord = new ArrayList<>();

    @Override
    public void taskElectionAnsweredFromRecord(
        final String adapterId,
        final String workflowModuleId,
        final String bpmnProcessId,
        final String operation) {

      answeredFromRecord
          .add(new AnsweredElection(adapterId, workflowModuleId, bpmnProcessId, operation));

    }

  }

  /** The outbox, reduced to what it is here: what phase two was planned with. */
  static class CapturingOutbox implements PhaseTwoOutbox {

    final List<PhaseTwoCall> scheduled = new ArrayList<>();

    @Override
    public boolean schedule(
        final PhaseTwoCall call) {

      return scheduled.add(call);

    }

  }

  private final InMemoryDeliveryLog deliveryLog = new InMemoryDeliveryLog();

  private final CapturingOutbox outbox = new CapturingOutbox();

  private final RecordingMetrics metrics = new RecordingMetrics();

  private static AggregatePersistenceAware<Object> persistence() {

    return new AggregatePersistenceAware<>() {

      @Override
      public Class<Object> getAggregateClass() {

        return Object.class;

      }

      @Override
      public Class<?> getAggregateIdType() {

        return String.class;

      }

      @Override
      public Object save(
          final Object workflowAggregate) {

        return workflowAggregate;

      }

      @Override
      public Object getAggregateId(
          final Object workflowAggregate) {

        return AGGREGATE;

      }

    };

  }

  private static MigrationAdapterProperties properties() {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of(ADAPTER))
        .build();
    properties.validateAndLink();
    return properties;

  }

  private MigrationProcessService<Object> serviceWith(
      final ProbeAdapter adapter,
      final TaskDeliveryLog log) {

    final var service = MigrationProcessService
        .forBpmnProcess(MODULE, PROCESS, Object.class)
        .properties(properties())
        .aggregatePersistence(persistence())
        .processServices(List.of(adapter))
        .phaseTwoOutboxResolver(new PhaseTwoOutboxResolver() {

          @Override
          public PhaseTwoOutbox resolveFor(
              final Class<?> workflowAggregateClass) {

            return outbox;

          }

          @Override
          public String remediesDescription() {

            return "";

          }

        })
        .taskDeliveryLogResolver(new TaskDeliveryLogResolver() {

          @Override
          public TaskDeliveryLog resolveFor(
              final Class<?> workflowAggregateClass) {

            return log;

          }

          @Override
          public String remediesDescription() {

            return "";

          }

        })
        .build();
    service.setMetrics(metrics);
    return service;

  }

  /**
   * The primary process service of a workflow service which also serves a called
   * process - what the platform integration builds and hands the served ids to.
   *
   * @param adapter The one configured adapter
   * @return The service of the PRIMARY id, which is the one the application calls
   */
  private MigrationProcessService<Object> primaryServiceOfAServiceWithACalledProcess(
      final ProbeAdapter adapter) {

    final var service = serviceWith(adapter, deliveryLog);
    service.setServedBpmnProcessIds(List.of(PROCESS, SECONDARY_PROCESS));
    return service;

  }

  /**
   * The record of a task which the handler left open, as the core writes it while the
   * handler runs.
   *
   * @param adapterId The adapter which delivered
   * @param taskId The task the record is about
   */
  private void recordOpenTaskOf(
      final String adapterId,
      final String taskId) {

    recordOpenTaskOf(adapterId, PROCESS, taskId);

  }

  /**
   * The same record, written by the instance of the BPMN process which delivered the
   * task - which is the secondary id where a called process handed the task out.
   *
   * @param adapterId The adapter which delivered
   * @param bpmnProcessId The process which delivered
   * @param taskId The task the record is about
   */
  private void recordOpenTaskOf(
      final String adapterId,
      final String bpmnProcessId,
      final String taskId) {

    deliveryLog
        .record(
            new TaskDelivery(
                "%s|%s|%s|CREATED|%s".formatted(adapterId, MODULE, bpmnProcessId,
                    taskId), adapterId, MODULE, bpmnProcessId, AGGREGATE, "awaitCompletion", taskId, "COMPLETION_PENDING", null, null, Instant
                        .now(), null));

  }

  /** What the process service logged while the work ran. */
  private static List<String> loggedBy(
      final Runnable work) {

    final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logWatcher.start();
    // the process service says some of this itself and lets its collaborator say the rest,
    // so both are listened to - which class a message comes from is not what is under test
    final var loggers = java.util.List
        .of(
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MigrationProcessService.class),
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DeliveryRecords.class));
    loggers.forEach(logger -> logger.addAppender(logWatcher));
    try {
      work.run();
    } finally {
      loggers.forEach(ch.qos.logback.classic.Logger::detachAndStopAllAppenders);
    }
    return logWatcher.list
        .stream()
        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
        .toList();

  }

  @Test
  @DisplayName("The adapter of an open record completes the task, and no BPMS is asked which holds it")
  public void anOpenRecordElectsTheAdapterWithoutAnyProbe() {

    final var adapter = new ProbeAdapter(ADAPTER);
    final var service = serviceWith(adapter, deliveryLog);
    recordOpenTaskOf(ADAPTER, TASK);

    service.completeTask(new Object(), TASK);

    assertEquals(0, adapter.probes.get(), "the record answered what the walk would have asked for");
    assertEquals(
        1,
        adapter.operations.phaseOneOf(PhaseOperation.COMPLETE_TASK).size(),
        "the adapter the record names runs its phase one as it always did");
    assertEquals(1, outbox.scheduled.size(), "phase two is planned as it always was");

  }

  @Test
  @DisplayName("Without a record the adapters are probed, exactly as before")
  public void withoutARecordTheAdaptersAreProbed() {

    final var adapter = new ProbeAdapter(ADAPTER);
    final var service = serviceWith(adapter, deliveryLog);

    service.completeTask(new Object(), TASK);

    assertEquals(1, adapter.probes.get(), "nothing is written down about this task, so it is asked about");
    assertEquals(1, adapter.operations.phaseOneOf(PhaseOperation.COMPLETE_TASK).size());

  }

  @Test
  @DisplayName("A store which cannot be queried by a task leaves the probe where it was")
  public void aStoreWithoutTheLookupLeavesTheProbeWhereItWas() {

    final var adapter = new ProbeAdapter(ADAPTER);
    final var service = serviceWith(adapter, new LogWithoutTaskLookup());

    service.completeTask(new Object(), TASK);

    assertEquals(1, adapter.probes.get(), "the default of the SPI answers nothing, and nothing is not an answer");

  }

  @Test
  @DisplayName("A record naming an adapter which is not prioritized any more falls back to the walk")
  public void aRecordOfAnUnconfiguredAdapterFallsBackToTheWalk() {

    final var adapter = new ProbeAdapter(ADAPTER);
    final var service = serviceWith(adapter, deliveryLog);
    recordOpenTaskOf("the-bpms-we-left", TASK);

    service.completeTask(new Object(), TASK);

    assertEquals(1, adapter.probes.get(), "an adapter nobody prioritizes cannot serve the operation");

  }

  @Test
  @DisplayName("A user task is elected from its record too")
  public void aUserTaskIsElectedFromItsRecord() {

    final var adapter = new ProbeAdapter(ADAPTER);
    final var service = serviceWith(adapter, deliveryLog);
    recordOpenTaskOf(ADAPTER, TASK);

    service.completeUserTask(new Object(), TASK);

    assertEquals(0, adapter.probes.get());
    assertEquals(1, adapter.operations.phaseOneOf(PhaseOperation.COMPLETE_USER_TASK).size());

  }

  @Test
  @DisplayName("The record is closed after phase two succeeded, not when the caller asked")
  public void theRecordIsClosedAfterPhaseTwoAndNotBefore() {

    final var adapter = new ProbeAdapter(ADAPTER);
    final var service = serviceWith(adapter, deliveryLog);
    recordOpenTaskOf(ADAPTER, TASK);

    service.completeTask(new Object(), TASK);

    assertNull(
        deliveryLog.recordOfTask(MODULE, PROCESS, AGGREGATE, TASK).orElseThrow().taskClosedAt(),
        "until phase two ran the task is open for the BPMS, and its redeliveries renew the lock on it");

    final var planned = outbox.scheduled.get(0);
    service
        .executePhaseTwo(PhaseOperation.COMPLETE_TASK, AGGREGATE, planned.adapterId(), planned.args(), false);

    assertNotNull(
        deliveryLog.recordOfTask(MODULE, PROCESS, AGGREGATE, TASK).orElseThrow().taskClosedAt(),
        "the completion reached the BPMS, so the record says so from now on");

  }

  @Test
  @DisplayName("A second completion of a closed task is the warned no-op, without asking any BPMS")
  public void aClosedTaskIsTheWarnedNoOpWithoutAnyProbe() {

    final var adapter = new ProbeAdapter(ADAPTER);
    final var service = serviceWith(adapter, deliveryLog);
    recordOpenTaskOf(ADAPTER, TASK);
    service.completeTask(new Object(), TASK);
    final var planned = outbox.scheduled.get(0);
    service
        .executePhaseTwo(PhaseOperation.COMPLETE_TASK, AGGREGATE, planned.adapterId(), planned.args(), false);
    final var probesSoFar = adapter.probes.get();
    final var phaseOneSoFar = adapter.operations.phaseOneOf(PhaseOperation.COMPLETE_TASK).size();

    final var messages = loggedBy(() -> service.completeTask(new Object(), TASK));

    assertEquals(probesSoFar, adapter.probes.get(), "nobody is asked about a task VanillaBP closed itself");
    assertEquals(
        phaseOneSoFar,
        adapter.operations.phaseOneOf(PhaseOperation.COMPLETE_TASK).size(),
        "a no-op reaches no adapter");
    assertTrue(
        messages.stream().anyMatch(message -> message.contains("already completed")),
        "the caller hears the same warning as before: "
            + messages);

  }

  @Test
  @DisplayName("A store which cannot mark the task costs a probe, not the operation")
  public void aStoreFailingToMarkTheTaskDoesNotFailTheDispatch() {

    final var failingToMark = new InMemoryDeliveryLog() {

      @Override
      public int markTaskClosed(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String workflowAggregateId,
          final String taskId) {

        throw new IllegalStateException("the store is unreachable");

      }

    };
    final var adapter = new ProbeAdapter(ADAPTER);
    final var service = serviceWith(adapter, failingToMark);
    failingToMark
        .record(
            new TaskDelivery(
                "key", ADAPTER, MODULE, PROCESS, AGGREGATE, "awaitCompletion", TASK, "COMPLETION_PENDING", null, null, Instant
                    .now(), null));

    service.completeTask(new Object(), TASK);
    final var planned = outbox.scheduled.get(0);
    final var messages = loggedBy(
        () -> service
            .executePhaseTwo(PhaseOperation.COMPLETE_TASK, AGGREGATE, planned.adapterId(), planned.args(), false));

    assertEquals(
        1,
        adapter.operations.phaseTwoOf(PhaseOperation.COMPLETE_TASK).size(),
        "the completion reached the BPMS - repeating it because a note failed would be the worse mistake");
    assertTrue(
        messages.stream().anyMatch(message -> message.contains("delivery record could not be marked")),
        "what was lost is said out loud: "
            + messages);

  }

  @Test
  @DisplayName("An election the record answered is counted, and one which walked is not")
  public void anAnswerFromTheRecordIsCounted() {

    final var adapter = new ProbeAdapter(ADAPTER);
    final var service = serviceWith(adapter, deliveryLog);
    recordOpenTaskOf(ADAPTER, TASK);

    service.completeTask(new Object(), TASK);

    assertEquals(
        List
            .of(
                new RecordingMetrics.AnsweredElection(
                    ADAPTER, MODULE, PROCESS, PhaseOperation.COMPLETE_TASK.name())),
        metrics.answeredFromRecord,
        "the number which says whether reading the record was worth anything");

    service.completeTask(new Object(), "a-task-nobody-wrote-a-record-for");

    assertEquals(
        1,
        metrics.answeredFromRecord.size(),
        "a walk is not counted: a store which cannot be queried by a task answers nothing either, "
            + "and calling that a miss would name a defect where there is none");

  }

  @Test
  @DisplayName("An aggregate push into the scope of an open task elects that task's adapter")
  public void anAggregatePushNamingATaskIsElectedFromTheRecord() {

    final var adapter = new ProbeAdapter(ADAPTER);
    final var service = serviceWith(adapter, deliveryLog);
    recordOpenTaskOf(ADAPTER, TASK);

    service.aggregateChanged(new Object(), TASK);

    assertEquals(
        0,
        adapter.probes.get(),
        "the call names a task, so the record answers it - on Camunda 8 that saves a search against "
            + "the secondary storage");
    assertEquals(
        1,
        adapter.operations.phaseOneOf(PhaseOperation.AGGREGATE_CHANGED).size(),
        "the adapter the record names pushes the values as it always did");
    assertEquals(
        List
            .of(
                new RecordingMetrics.AnsweredElection(
                    ADAPTER, MODULE, PROCESS, PhaseOperation.AGGREGATE_CHANGED.name())),
        metrics.answeredFromRecord);

  }

  @Test
  @DisplayName("An aggregate push without a task id probes, exactly as before")
  public void anAggregatePushWithoutATaskProbesAsItAlwaysDid() {

    final var adapter = new ProbeAdapter(ADAPTER);
    final var service = serviceWith(adapter, deliveryLog);
    recordOpenTaskOf(ADAPTER, TASK);

    service.aggregateChanged(new Object(), null);

    assertEquals(
        1,
        adapter.probes.get(),
        "nothing names a task here, and no workflow is ever located from a record");
    assertTrue(metrics.answeredFromRecord.isEmpty());

  }

  @Test
  @DisplayName("A task which is over says nothing about the workflow around it")
  public void aClosedTaskDoesNotDecideAboutTheWorkflowAroundIt() {

    final var adapter = new ProbeAdapter(ADAPTER);
    final var service = serviceWith(adapter, deliveryLog);
    recordOpenTaskOf(ADAPTER, TASK);
    deliveryLog.markTaskClosed(MODULE, PROCESS, AGGREGATE, TASK);

    service.aggregateChanged(new Object(), TASK);

    assertEquals(
        1,
        adapter.probes.get(),
        "the scope the values go into outlives the task, and so may the workflow");
    assertEquals(
        1,
        adapter.operations.phaseOneOf(PhaseOperation.AGGREGATE_CHANGED).size(),
        "so the push happens rather than being the no-op a completion of that task would be");

  }

  @Test
  @DisplayName("An aggregate push leaves the record of the task it names open")
  public void anAggregatePushLeavesTheRecordOfItsTaskOpen() {

    final var adapter = new ProbeAdapter(ADAPTER);
    final var service = serviceWith(adapter, deliveryLog);
    recordOpenTaskOf(ADAPTER, TASK);

    service.aggregateChanged(new Object(), TASK);
    final var planned = outbox.scheduled.get(0);
    service
        .executePhaseTwo(PhaseOperation.AGGREGATE_CHANGED, AGGREGATE, planned.adapterId(), planned.args(), false);

    assertNull(
        deliveryLog.recordOfTask(MODULE, PROCESS, AGGREGATE, TASK).orElseThrow().taskClosedAt(),
        "a push completes nothing - the BPMS still hands that task out, and its redeliveries are "
            + "still answered from this record");

  }

  @Test
  @DisplayName("A task a called process delivered is completed on the primary service without any probe")
  public void aRecordOfACalledProcessAnswersTheOperationOnThePrimaryService() {

    final var adapter = new ProbeAdapter(ADAPTER);
    final var service = primaryServiceOfAServiceWithACalledProcess(adapter);
    recordOpenTaskOf(ADAPTER, SECONDARY_PROCESS, TASK);

    service.completeTask(new Object(), TASK);

    assertEquals(
        0,
        adapter.probes.get(),
        "the record sits under the id of the process which delivered the task, and the read "
            + "looks there too");
    assertEquals(
        1,
        adapter.operations.phaseOneOf(PhaseOperation.COMPLETE_TASK).size(),
        "the adapter the record names completes the task as it does for the primary process");

  }

  @Test
  @DisplayName("A service which serves one process alone still reads that one only")
  public void aRecordOfAnotherProcessIsNotReadWithoutTheServedIds() {

    final var adapter = new ProbeAdapter(ADAPTER);
    final var service = serviceWith(adapter, deliveryLog);
    recordOpenTaskOf(ADAPTER, SECONDARY_PROCESS, TASK);

    service.completeTask(new Object(), TASK);

    assertEquals(
        1,
        adapter.probes.get(),
        "nothing declares that process here, so its records are none of this service's business");

  }

  @Test
  @DisplayName("The record of a called process is closed after phase two, under the id it lives at")
  public void theRecordOfACalledProcessIsClosedAfterPhaseTwo() {

    final var adapter = new ProbeAdapter(ADAPTER);
    final var service = primaryServiceOfAServiceWithACalledProcess(adapter);
    recordOpenTaskOf(ADAPTER, SECONDARY_PROCESS, TASK);

    service.completeTask(new Object(), TASK);
    final var planned = outbox.scheduled.get(0);
    service
        .executePhaseTwo(PhaseOperation.COMPLETE_TASK, AGGREGATE, planned.adapterId(), planned.args(), false);

    assertNotNull(
        deliveryLog
            .recordOfTask(MODULE, SECONDARY_PROCESS, AGGREGATE, TASK)
            .orElseThrow()
            .taskClosedAt(),
        "the row nobody used to find stayed open, counted as an open task and aged forever");

  }

  @Test
  @DisplayName("A second completion of that task is the warned no-op, still without a probe")
  public void aClosedRecordOfACalledProcessIsReadTheSameWay() {

    final var adapter = new ProbeAdapter(ADAPTER);
    final var service = primaryServiceOfAServiceWithACalledProcess(adapter);
    recordOpenTaskOf(ADAPTER, SECONDARY_PROCESS, TASK);
    service.completeTask(new Object(), TASK);
    final var planned = outbox.scheduled.get(0);
    service
        .executePhaseTwo(PhaseOperation.COMPLETE_TASK, AGGREGATE, planned.adapterId(), planned.args(), false);
    final var phaseOneSoFar = adapter.operations.phaseOneOf(PhaseOperation.COMPLETE_TASK).size();
    // phase two elects by probing whatever a record said in phase one, so what the second
    // completion may add to is the count the dispatch left behind
    final var probesSoFar = adapter.probes.get();

    final var messages = loggedBy(() -> service.completeTask(new Object(), TASK));

    assertEquals(probesSoFar, adapter.probes.get(), "nobody is asked about a task VanillaBP closed itself");
    assertEquals(
        phaseOneSoFar,
        adapter.operations.phaseOneOf(PhaseOperation.COMPLETE_TASK).size(),
        "a no-op reaches no adapter");
    assertTrue(
        messages.stream().anyMatch(message -> message.contains("already completed")),
        "the caller hears the same warning as for the primary process: "
            + messages);

  }

}
