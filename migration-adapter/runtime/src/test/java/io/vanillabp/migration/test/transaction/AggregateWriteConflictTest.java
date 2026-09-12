package io.vanillabp.migration.test.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.transaction.AggregateWrite;
import io.vanillabp.integration.adapter.migration.transaction.ConcurrentTokenCheck;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.spi.VersionAttribute;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.WorkflowTask;
import lombok.Getter;

/**
 * Two branches of one workflow write the same workflow aggregate. What is
 * pinned here is the CORE's part of it:
 * <ul>
 * <li>a version conflict on the commit VanillaBP owns is recognized by asking the
 * platform, named by one guiding message and then propagated UNCHANGED - nothing is
 * retried, the BPMS decides what happens next;</li>
 * <li>a failure the platform does not classify as a conflict passes without any
 * message;</li>
 * <li>elements which can produce a second token are warned about while wiring - once
 * per BPMN process, and only where the aggregate has no version attribute, since a
 * version attribute turns the silent overwrite into the exception above.</li>
 * </ul>
 */
@ExtendWith(SuppressOutputExtension.class)
public class AggregateWriteConflictTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String TASK = "task";

  /**
   * Stands in for <code>jakarta.persistence.Version</code> respectively
   * <code>org.springframework.data.annotation.Version</code>, which the core must not
   * depend on - it recognizes them by their name.
   */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({
      ElementType.FIELD, ElementType.METHOD
  })
  public @interface Version {
  }

  public static class Aggregate {

    @Getter
    String id;

    int invocations;

  }

  public static class VersionedAggregate extends Aggregate {

    @Version
    long version;

  }

  static class InMemoryPersistence implements AggregatePersistenceAware<Aggregate> {

    final Map<Object, Aggregate> aggregates = new HashMap<>();

    @Override
    public Class<Aggregate> getAggregateClass() {
      return Aggregate.class;
    }

    @Override
    public String getAggregateIdName() {
      return "id";
    }

    @Override
    public Class<?> getAggregateIdType() {
      return String.class;
    }

    @Override
    public Object getAggregateId(
        final Aggregate aggregate) {
      return aggregate.id;
    }

    @Override
    public Aggregate save(
        final Aggregate aggregate) {
      aggregates.put(aggregate.id, aggregate);
      return aggregate;
    }

    @Override
    public Aggregate loadById(
        final Object aggregateId) {
      return aggregates.get(aggregateId);
    }

  }

  /**
   * A platform whose commit fails: the work runs, and what the commit produces is
   * thrown at the caller of the runner - which is where a version conflict of a
   * persistence layer appears.
   */
  static class FailingCommitTransactionRunner implements TransactionRunner {

    private final RuntimeException commitFailure;

    private final boolean classifyAsConflict;

    int classifierAsked = 0;

    FailingCommitTransactionRunner(
        final RuntimeException commitFailure,
        final boolean classifyAsConflict) {
      this.commitFailure = commitFailure;
      this.classifyAsConflict = classifyAsConflict;
    }

    @Override
    public <T> T requireNew(
        final Supplier<T> work) {
      work.get();
      throw commitFailure;
    }

    @Override
    public <T> T inCurrent(
        final Supplier<T> work) {
      return work.get();
    }

    @Override
    public boolean isRollbackOnly() {
      return false;
    }

    @Override
    public boolean isConcurrentModification(
        final Throwable failure) {
      ++classifierAsked;
      return classifyAsConflict;
    }

  }

  public static class Service {

    @WorkflowTask(taskDefinition = TASK)
    public void task(
        final Aggregate aggregate) {

      ++aggregate.invocations;

    }

  }

  private final InMemoryPersistence persistence = new InMemoryPersistence();

  @Test
  @DisplayName("A version conflict is named once and the exception reaches the adapter unchanged")
  public void conflictIsReportedAndPropagated() {

    final var conflict = new IllegalStateException("Row was updated by another transaction");
    final var transactionRunner = new FailingCommitTransactionRunner(conflict, true);
    final var registry = registry(transactionRunner);
    final var aggregate = storeAggregate("aggregate-1");

    final var messages = new java.util.ArrayList<String>();
    final var thrown = assertThrows(
        IllegalStateException.class,
        () -> loggedBy(
            AggregateWrite.class,
            messages,
            () -> registry.invokeWorkflowTask(MODULE, PROCESS, context("aggregate-1"))));

    // the exception is NOT wrapped: adapters map it to their BPMS' retry semantics
    assertSame(conflict, thrown);
    assertEquals(1, transactionRunner.classifierAsked);
    // no retry inside VanillaBP - the handler ran exactly once
    assertEquals(1, aggregate.invocations);
    assertEquals(1, messages.size());
    final var message = messages.getFirst();
    assertTrue(message.contains(MODULE), message);
    assertTrue(message.contains(PROCESS), message);
    assertTrue(message.contains("aggregate-1"), message);
    assertTrue(message.contains(TASK), message);
    assertTrue(message.contains("does not retry"), message);
    assertTrue(message.contains("Workflow aggregates"), message);

  }

  @Test
  @DisplayName("A failure which is no version conflict passes without a message")
  public void otherFailuresArePassedThroughSilently() {

    final var failure = new IllegalArgumentException("something else");
    final var transactionRunner = new FailingCommitTransactionRunner(failure, false);
    final var registry = registry(transactionRunner);
    storeAggregate("aggregate-2");

    final var messages = new java.util.ArrayList<String>();
    final var thrown = assertThrows(
        IllegalArgumentException.class,
        () -> loggedBy(
            AggregateWrite.class,
            messages,
            () -> registry.invokeWorkflowTask(MODULE, PROCESS, context("aggregate-2"))));

    assertSame(failure, thrown);
    assertEquals(1, transactionRunner.classifierAsked);
    assertTrue(messages.isEmpty(), messages.toString());

  }

  @Test
  @DisplayName("A platform which does not classify keeps quiet instead of guessing")
  public void aPlatformWithoutAClassifierStaysSilent() {

    final var runner = new TransactionRunner() {

      @Override
      public <T> T requireNew(
          final Supplier<T> work) {
        return work.get();
      }

      @Override
      public <T> T inCurrent(
          final Supplier<T> work) {
        return work.get();
      }

      @Override
      public boolean isRollbackOnly() {
        return false;
      }

    };

    assertFalse(runner.isConcurrentModification(new IllegalStateException("conflict")));

  }

  @Test
  @DisplayName("Reported concurrent tokens warn about an aggregate without a version attribute")
  public void concurrentTokensWithoutVersionAttributeAreWarnedAbout() {

    final var registry = registry(new FailingCommitTransactionRunner(new IllegalStateException(), false));

    final var messages = new java.util.ArrayList<String>();
    loggedBy(
        ConcurrentTokenCheck.class,
        messages,
        () -> registry
            .reportConcurrentTokenElements(MODULE, PROCESS, List.of("Event_Reminder", "Gateway_Fork")));

    assertEquals(1, messages.size());
    final var message = messages.getFirst();
    assertTrue(message.contains("Event_Reminder"), message);
    assertTrue(message.contains("Gateway_Fork"), message);
    assertTrue(message.contains(Aggregate.class.getName()), message);
    assertTrue(message.contains("@DynamicUpdate"), message);
    assertTrue(message.contains("Workflow aggregates"), message);

  }

  @Test
  @DisplayName("The warning is given once per BPMN process, no matter how often an adapter reports")
  public void theWarningIsGivenOncePerProcess() {

    final var registry = registry(new FailingCommitTransactionRunner(new IllegalStateException(), false));

    final var messages = new java.util.ArrayList<String>();
    loggedBy(
        ConcurrentTokenCheck.class,
        messages,
        () -> {
          registry.reportConcurrentTokenElements(MODULE, PROCESS, List.of("Event_Reminder"));
          registry.reportConcurrentTokenElements(MODULE, PROCESS, List.of("Event_Reminder"));
        });

    assertEquals(1, messages.size());

  }

  @Test
  @DisplayName("An aggregate carrying a version attribute stays quiet, and so does a silent adapter")
  public void nothingIsWarnedAboutWhereThereIsNothingToSay() {

    final var registry = registry(new FailingCommitTransactionRunner(new IllegalStateException(), false));

    final var messages = new java.util.ArrayList<String>();
    loggedBy(
        ConcurrentTokenCheck.class,
        messages,
        () -> {
          // an adapter which cannot read models reports nothing
          registry.reportConcurrentTokenElements(MODULE, PROCESS, List.of());
          // a BPMN process no workflow service serves (reported by the wiring check)
          registry.reportConcurrentTokenElements(MODULE, "UnknownProcess", List.of("Gateway_Fork"));
        });

    assertTrue(messages.isEmpty(), messages.toString());
    // the version attribute of a super class counts as well, and the question the check
    // asks the persistence is answered from it
    assertTrue(VersionAttribute.isDeclaredBy(VersionedAggregate.class));
    assertFalse(VersionAttribute.isDeclaredBy(Aggregate.class));

  }

  @Test
  @DisplayName("The exceptions of a version conflict are recognized along the chain of causes")
  public void optimisticLockingIsRecognizedByName() {

    assertTrue(
        AggregateWrite
            .causedByOptimisticLocking(
                new IllegalStateException(
                    "commit failed", new jakarta.persistence.OptimisticLockException("Row 4711"))));
    assertFalse(AggregateWrite.causedByOptimisticLocking(new IllegalStateException("something else")));
    assertFalse(AggregateWrite.causedByOptimisticLocking(null));

  }

  private MigrationProcessService<Aggregate> processService() {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of("test-adapter", AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of("test-adapter"))
        .build();
    properties.validateAndLink();

    @SuppressWarnings("unchecked")
    final MigratableProcessService<Aggregate> adapter = mock(MigratableProcessService.class);
    lenient().when(adapter.getAdapterId()).thenReturn("test-adapter");

    return MigrationProcessService
        .forBpmnProcess(MODULE, PROCESS, Aggregate.class)
        .properties(properties)
        .aggregatePersistence(persistence)
        .processServices(List.of(adapter))
        .build();

  }

  private WorkflowTaskRegistry registry(
      final TransactionRunner transactionRunner) {

    final var registry = new WorkflowTaskRegistry(transactionRunner);
    registry
        .registerWorkflowService(MODULE, PROCESS, Service.class, Service::new, type -> null, processService());
    return registry;

  }

  private Aggregate storeAggregate(
      final String id) {

    final var aggregate = new Aggregate();
    aggregate.id = id;
    persistence.aggregates.put(id, aggregate);
    return aggregate;

  }

  private TaskInvocationContext context(
      final String aggregateId) {

    return new TaskInvocationContext() {

      @Override
      public String getWorkflowAggregateId() {
        return aggregateId;
      }

      @Override
      public String getTaskDefinition() {
        return TASK;
      }

    };

  }

  /**
   * Collects the messages the given class logged at WARN or above while the work ran
   * ("normal" logging is switched off during tests).
   */
  private void loggedBy(
      final Class<?> loggingClass,
      final List<String> messages,
      final Runnable work) {

    final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logWatcher.start();
    final var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(loggingClass);
    logger.addAppender(logWatcher);
    try {
      work.run();
    } finally {
      logger.detachAndStopAllAppenders();
      logWatcher.list
          .stream()
          .filter(event -> event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN))
          .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
          .forEach(messages::add);
    }

  }

}
