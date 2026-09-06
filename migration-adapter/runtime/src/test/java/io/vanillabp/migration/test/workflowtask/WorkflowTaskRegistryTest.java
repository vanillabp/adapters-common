package io.vanillabp.migration.test.workflowtask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.MultiInstanceElement;
import io.vanillabp.spi.service.MultiInstanceElementResolver;
import io.vanillabp.spi.service.MultiInstanceIndex;
import io.vanillabp.spi.service.MultiInstanceTotal;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowTask;
import lombok.Getter;

/**
 * Unit tests of the core-owned <code>&#64;WorkflowTask</code> handler model: method
 * scanning and parameter binding, the three invocation outcomes (completed / BPMN
 * error / failure), version-range matching and the two-directional wiring
 * validation with guiding messages.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowTaskRegistryTest {

  /**
   * The base class an aggregate inherits attributes from - the shape the attribute
   * resolution has to cope with, because a V1 application typically has its
   * ID and its tenant on a base entity.
   */
  public static class BaseAggregate {

    String tenantId;

  }

  public static class Aggregate extends BaseAggregate {

    @Getter
    String id;

    @Getter
    String processedBy;

    Object element;

    int index;

    int total;

    /**
     * Excluded from what the BPMS sees - which derives opt-out for the
     * whole class: everything else IS shared.
     */
    @io.vanillabp.spi.service.NoSyncWithBPMS
    @Getter
    String parameterValue;

    String taskId;

    TaskEvent.Event event;

    @Getter
    boolean urgent;

  }

  /**
   * Records requireNew/inCurrent usage and mimics the transactional contract: a
   * RuntimeException thrown by the work propagates (the "rollback"), a normal
   * return is the "commit".
   */
  static class RecordingTransactionRunner implements TransactionRunner {

    boolean requireNewUsed = false;

    boolean inCurrentUsed = false;

    /**
     * Mimics a transaction annotation of the application having marked the
     * transaction rollback-only while the handler ran.
     */
    boolean rollbackOnly = false;

    @Override
    public <T> T requireNew(
        final Supplier<T> work) {

      requireNewUsed = true;
      return work.get();

    }

    @Override
    public <T> T inCurrent(
        final Supplier<T> work) {

      inCurrentUsed = true;
      return work.get();

    }

    @Override
    public boolean isRollbackOnly() {

      return rollbackOnly;

    }

  }

  static class InMemoryPersistence implements AggregatePersistenceAware<Aggregate> {

    final Map<String, Aggregate> aggregates = new HashMap<>();

    boolean saved = false;

    /**
     * Makes {@link #loadById(Object)} fail - the sync seam has to answer with an
     * empty map instead of breaking a task completion.
     */
    boolean failLoad = false;

    @Override
    public Class<Aggregate> getAggregateClass() {
      return Aggregate.class;
    }

    @Override
    public String getAggregateIdName() {
      return "aggregateId";
    }

    @Override
    public Aggregate save(
        final Aggregate aggregate) {
      saved = true;
      aggregates.put(aggregate.id, aggregate);
      return aggregate;
    }

    @Override
    public Object getAggregateId(
        final Aggregate aggregate) {
      return aggregate.id;
    }

    @Override
    public Class<?> getAggregateIdType() {
      return String.class;
    }

    @Override
    public Aggregate loadById(
        final Object aggregateId) {
      if (failLoad) {
        throw new IllegalStateException("loading the aggregate failed");
      }
      return aggregates.get(aggregateId);
    }

  }

  static class SampleService {

    @WorkflowTask
    public void doSomething(
        final Aggregate aggregate) {

      aggregate.processedBy = "doSomething";

    }

    @WorkflowTask(taskDefinition = "explicitDefinition")
    public void byDefinition(
        final Aggregate aggregate) {

      aggregate.processedBy = "byDefinition";

    }

    @WorkflowTask(id = "Activity_4711")
    public void byActivityId(
        final Aggregate aggregate) {

      aggregate.processedBy = "byActivityId";

    }

    @WorkflowTask
    public void fails(
        final Aggregate aggregate) {

      aggregate.processedBy = "fails";
      throw new IllegalArgumentException("boom");

    }

    @WorkflowTask
    public void bpmnError(
        final Aggregate aggregate) {

      aggregate.processedBy = "bpmnError";
      throw new TaskException("SomethingWrong", "ERR-42");

    }

    @WorkflowTask
    public void asyncTask(
        final Aggregate aggregate,
        @TaskId final String taskId) {

      aggregate.processedBy = "asyncTask";
      aggregate.taskId = taskId;

    }

    @WorkflowTask
    public void withBindings(
        final Aggregate aggregate,
        @TaskParam("status") final String status,
        @TaskEvent final TaskEvent.Event event,
        @MultiInstanceIndex("items") final int index,
        @MultiInstanceTotal("items") final int total,
        @MultiInstanceElement("items") final Object element) {

      aggregate.processedBy = "withBindings";
      aggregate.parameterValue = status;
      aggregate.event = event;
      aggregate.index = index;
      aggregate.total = total;
      aggregate.element = element;

    }

    @WorkflowTask
    public void withResolver(
        final Aggregate aggregate,
        @MultiInstanceElement(resolverBean = ItemResolver.class) final Object element) {

      aggregate.processedBy = "withResolver";
      aggregate.element = element;

    }

  }

  public interface ItemResolver extends MultiInstanceElementResolver<Aggregate, Object> {
  }

  static class VersionedService {

    @WorkflowTask(taskDefinition = "versioned", version = "1-2")
    public void oldVersions(
        final Aggregate aggregate) {

      aggregate.processedBy = "oldVersions";

    }

    @WorkflowTask(taskDefinition = "versioned", version = ">2")
    public void newVersions(
        final Aggregate aggregate) {

      aggregate.processedBy = "newVersions";

    }

  }

  /**
   * Two methods serving ONE BPMN element, one of them keeping the task open: what an
   * adapter has to treat as "this element cannot complete on return".
   */
  static class MixedAsyncService {

    @WorkflowTask(taskDefinition = "mixedVersioned", version = "1")
    public void firstVersion(
        final Aggregate aggregate) {

      aggregate.processedBy = "firstVersion";

    }

    @WorkflowTask(taskDefinition = "mixedVersioned", version = ">1")
    public void laterVersions(
        final Aggregate aggregate,
        @TaskId final String taskId) {

      aggregate.processedBy = "laterVersions";
      aggregate.taskId = taskId;

    }

  }

  /**
   * A V1 aggregate may compute an attribute in its getter - and that computation may
   * fail. An expression resolved while the engine transaction runs must not be the
   * place where that surfaces as an engine failure.
   */
  public static class BrokenGetterAggregate extends Aggregate {

    public String getBoom() {
      throw new IllegalStateException("computing the attribute failed");
    }

  }

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private RecordingTransactionRunner transactionRunner;

  private InMemoryPersistence persistence;

  private WorkflowTaskRegistry registry;

  private final SampleService serviceBean = new SampleService();

  private final Map<Class<?>, Object> beans = new HashMap<>();

  @BeforeEach
  public void setUpRegistry() {

    transactionRunner = new RecordingTransactionRunner();
    persistence = new InMemoryPersistence();
    registry = new WorkflowTaskRegistry(transactionRunner);
    registry.registerWorkflowService(
        MODULE,
        PROCESS,
        SampleService.class,
        () -> serviceBean,
        beans::get,
        createProcessService());

    final var aggregate = new Aggregate();
    aggregate.id = "4711";
    persistence.aggregates.put("4711", aggregate);

  }

  private MigrationProcessService<Aggregate> createProcessService() {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of("test-adapter", AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of("test-adapter"))
        .build();
    properties.validateAndLink();
    final var adapterProcessService = new MigratableProcessService<Aggregate>() {

      @Override
      public String getAdapterId() {
        return "test-adapter";
      }

      @Override
      public java.util.Map<io.vanillabp.integration.spi.PhaseOperation, io.vanillabp.integration.adapter.spi.PhaseOperationHandler<Aggregate>> phaseOperations() {
        return io.vanillabp.migration.test.TestPhaseOperations.doingNothing();
      }

      @Override
      public WorkflowAwareness awarenessOfTask(
          final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
          final Object workflowAggregateId,
          final String taskId) {
        return WorkflowAwareness.UNKNOWN_TO_BPMS;
      }

      @Override
      public WorkflowAwareness awarenessOfWorkflow(
          final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
          final io.vanillabp.integration.spi.AggregatePersistenceAware<Aggregate> aggregatePersistence,
          final Object workflowAggregateId) {
        return WorkflowAwareness.UNKNOWN_TO_BPMS;
      }

      @Override
      public io.vanillabp.integration.adapter.spi.WorkflowAwareness awarenessOfUserTask(
          final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
          final Object workflowAggregateId,
          final String taskId) {
        return io.vanillabp.integration.adapter.spi.WorkflowAwareness.UNKNOWN_TO_BPMS;
      }

    };
    return MigrationProcessService
        .forBpmnProcess(MODULE, PROCESS, Aggregate.class)
        .properties(properties)
        .aggregatePersistence(persistence)
        .processServices(List.of(adapterProcessService))
        .build();

  }

  private TaskInvocationContext context(
      final String taskDefinition) {

    return new TaskInvocationContext() {

      @Override
      public String getTaskDefinition() {
        return taskDefinition;
      }

      @Override
      public String getWorkflowAggregateId() {
        return "4711";
      }

    };

  }

  @Nested
  @DisplayName("Invocation outcomes")
  class InvocationOutcomes {

    @Test
    @DisplayName("Normal return completes the task and saves the aggregate")
    public void normalReturnCompletes() {

      final var outcome = registry.invokeWorkflowTask(MODULE, PROCESS, context("doSomething"));

      assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, outcome.kind());
      assertEquals("doSomething", persistence.aggregates.get("4711").processedBy);
      assertTrue(persistence.saved);
      assertTrue(transactionRunner.requireNewUsed);
      assertFalse(transactionRunner.inCurrentUsed);

    }

    @Test
    @DisplayName("TaskException yields a BPMN-error outcome and STILL saves the aggregate")
    public void taskExceptionYieldsBpmnErrorAndCommits() {

      final var outcome = registry.invokeWorkflowTask(MODULE, PROCESS, context("bpmnError"));

      assertEquals(WorkflowTaskOutcome.Kind.BPMN_ERROR, outcome.kind());
      assertEquals("ERR-42", outcome.errorCode());
      assertEquals("SomethingWrong", outcome.errorName());
      // the V1 contract: aggregate changes commit although the handler threw
      assertEquals("bpmnError", persistence.aggregates.get("4711").processedBy);
      assertTrue(persistence.saved);

    }

    @Test
    @DisplayName("A rollback-only transaction fails the task with a guiding message")
    public void rollbackOnlyTransactionFailsGuiding() {

      // what a transaction annotation of the application does to VanillaBP's
      // transaction as soon as an exception passes it, e.g. a TaskException
      transactionRunner.rollbackOnly = true;

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> registry.invokeWorkflowTask(MODULE, PROCESS, context("doSomething")));

      assertTrue(exception.getMessage().contains("marked rollback-only"), exception.getMessage());
      assertTrue(exception.getMessage().contains("doSomething"), exception.getMessage());
      assertTrue(exception.getMessage().contains(PROCESS), exception.getMessage());
      assertTrue(exception.getMessage().contains(MODULE), exception.getMessage());
      assertTrue(exception.getMessage().contains("TaskException"), exception.getMessage());

    }

    @Test
    @DisplayName("A rollback-only transaction fails the TaskException path too, instead of reporting a BPMN error")
    public void rollbackOnlyTransactionFailsTheTaskExceptionPath() {

      transactionRunner.rollbackOnly = true;

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> registry.invokeWorkflowTask(MODULE, PROCESS, context("bpmnError")));

      assertTrue(exception.getMessage().contains("marked rollback-only"), exception.getMessage());
      assertTrue(exception.getMessage().contains("bpmnError"), exception.getMessage());

    }

    @Test
    @DisplayName("Any other exception propagates and the aggregate is NOT saved")
    public void otherExceptionPropagatesWithoutSaving() {

      final var exception = assertThrows(
          IllegalArgumentException.class,
          () -> registry.invokeWorkflowTask(MODULE, PROCESS, context("fails")));

      assertEquals("boom", exception.getMessage());
      assertFalse(persistence.saved);

    }

    @Test
    @DisplayName("A @TaskId method yields COMPLETION_PENDING and receives the task's ID")
    public void asyncTaskYieldsCompletionPending() {

      final var outcome = registry.invokeWorkflowTask(MODULE, PROCESS, new TaskInvocationContext() {

        @Override
        public String getTaskDefinition() {
          return "asyncTask";
        }

        @Override
        public String getWorkflowAggregateId() {
          return "4711";
        }

        @Override
        public String getTaskId() {
          return "task-0815";
        }

      });

      assertEquals(WorkflowTaskOutcome.Kind.COMPLETION_PENDING, outcome.kind());
      assertEquals("task-0815", persistence.aggregates.get("4711").taskId);

    }

    @Test
    @DisplayName("runInCurrentTransaction() routes through TransactionRunner.inCurrent")
    public void currentTransactionRequested() {

      registry.invokeWorkflowTask(MODULE, PROCESS, new TaskInvocationContext() {

        @Override
        public String getTaskDefinition() {
          return "doSomething";
        }

        @Override
        public String getWorkflowAggregateId() {
          return "4711";
        }

        @Override
        public boolean runInCurrentTransaction() {
          return true;
        }

      });

      assertTrue(transactionRunner.inCurrentUsed);
      assertFalse(transactionRunner.requireNewUsed);

    }

    @Test
    @DisplayName("A missing aggregate fails with a guiding message")
    public void missingAggregateFails() {

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> registry.invokeWorkflowTask(MODULE, PROCESS, new TaskInvocationContext() {

            @Override
            public String getTaskDefinition() {
              return "doSomething";
            }

            @Override
            public String getWorkflowAggregateId() {
              return "no-such-id";
            }

          }));

      assertTrue(exception.getMessage().contains("no-such-id"));
      assertTrue(exception.getMessage().contains(Aggregate.class.getName()));

    }

    @Test
    @DisplayName("An unknown task definition fails naming the registered methods")
    public void unknownTaskDefinitionFails() {

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> registry.invokeWorkflowTask(MODULE, PROCESS, context("noSuchTask")));

      assertTrue(exception.getMessage().contains("noSuchTask"));
      assertTrue(exception.getMessage().contains("doSomething"));

    }

    @Test
    @DisplayName("An unknown BPMN process fails naming the known processes")
    public void unknownProcessFails() {

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> registry.invokeWorkflowTask(MODULE, "NoSuchProcess", context("doSomething")));

      assertTrue(exception.getMessage().contains("NoSuchProcess"));
      assertTrue(exception.getMessage().contains(PROCESS));

    }

  }

  @Nested
  @DisplayName("Parameter binding")
  class ParameterBinding {

    @Test
    @DisplayName("@TaskParam, @TaskEvent and @MultiInstance* parameters are bound from the context")
    public void allBindingsResolved() {

      final var multiInstances = new LinkedHashMap<String, MultiInstanceValue>();
      multiInstances.put("items", new MultiInstanceValue("item-2", 2, 5));

      registry.invokeWorkflowTask(MODULE, PROCESS, new TaskInvocationContext() {

        @Override
        public String getTaskDefinition() {
          return "withBindings";
        }

        @Override
        public String getWorkflowAggregateId() {
          return "4711";
        }

        @Override
        public Object getTaskParameter(
            final String name) {
          return "status".equals(name)
              ? "APPROVED"
              : null;
        }

        @Override
        public Map<String, MultiInstanceValue> getMultiInstances() {
          return multiInstances;
        }

      });

      final var aggregate = persistence.aggregates.get("4711");
      assertEquals("APPROVED", aggregate.parameterValue);
      assertEquals(TaskEvent.Event.CREATED, aggregate.event);
      assertEquals(2, aggregate.index);
      assertEquals(5, aggregate.total);
      assertEquals("item-2", aggregate.element);

    }

    @Test
    @DisplayName("@MultiInstanceElement(resolverBean = ...) resolves via the bean resolver")
    public void resolverBeanResolved() {

      final var resolved = new Object();
      beans.put(ItemResolver.class, new ItemResolver() {

        @Override
        public java.util.Collection<String> getNames() {
          return List.of("items");
        }

        @Override
        public Object resolve(
            final Aggregate workflowAggregate,
            final Map<String, MultiInstanceElementResolver.MultiInstance<Object>> multiInstances) {
          return resolved;
        }

      });

      registry.invokeWorkflowTask(MODULE, PROCESS, context("withResolver"));

      assertSame(resolved, persistence.aggregates.get("4711").element);

    }

    @Test
    @DisplayName("A missing multi-instance context fails with a guiding message")
    public void missingMultiInstanceFails() {

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> registry.invokeWorkflowTask(MODULE, PROCESS, context("withBindings")));

      assertTrue(exception.getMessage().contains("items"));
      assertTrue(exception.getMessage().contains("withBindings"));

    }

    @Test
    @DisplayName("An unbindable parameter fails at registration with a guiding message")
    public void unbindableParameterFailsAtRegistration() {

      class BrokenService {

        @WorkflowTask
        public void broken(
            final Aggregate aggregate,
            final String unannotated) {
        }

      }

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> registry.registerWorkflowService(
              MODULE,
              "OtherProcess",
              BrokenService.class,
              BrokenService::new,
              beans::get,
              createProcessService()));

      assertTrue(exception.getMessage().contains("broken"));
      assertTrue(exception.getMessage().contains("@TaskParam"));

    }

    @Test
    @DisplayName("Two methods wired to the same task definition fail at registration")
    public void duplicateWiringFailsAtRegistration() {

      class DuplicateService {

        @WorkflowTask(taskDefinition = "doSomething")
        public void collides(
            final Aggregate aggregate) {
        }

      }

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> registry.registerWorkflowService(
              MODULE,
              PROCESS,
              DuplicateService.class,
              DuplicateService::new,
              beans::get,
              createProcessService()));

      assertTrue(exception.getMessage().contains("doSomething"));
      assertTrue(exception.getMessage().contains("version"));

    }

  }

  @Nested
  @DisplayName("Version ranges")
  class VersionRanges {

    @BeforeEach
    public void registerVersionedService() {

      registry.registerWorkflowService(
          MODULE,
          "VersionedProcess",
          VersionedService.class,
          VersionedService::new,
          beans::get,
          createProcessService());

    }

    private TaskInvocationContext versionedContext(
        final String version) {

      return new TaskInvocationContext() {

        @Override
        public String getTaskDefinition() {
          return "versioned";
        }

        @Override
        public String getWorkflowAggregateId() {
          return "4711";
        }

        @Override
        public String getProcessVersion() {
          return version;
        }

      };

    }

    @Test
    @DisplayName("The handler matching the process version wins")
    public void versionRangesAreHonored() {

      registry.invokeWorkflowTask(MODULE, "VersionedProcess", versionedContext("2"));
      assertEquals("oldVersions", persistence.aggregates.get("4711").processedBy);

      registry.invokeWorkflowTask(MODULE, "VersionedProcess", versionedContext("3"));
      assertEquals("newVersions", persistence.aggregates.get("4711").processedBy);

    }

    @Test
    @DisplayName("A BPMS reporting no version reaches no handler naming versions")
    public void withoutAReportedVersionRangedHandlersAreNotCalled() {

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> registry.invokeWorkflowTask(MODULE, "VersionedProcess", versionedContext(null)));
      assertTrue(exception.getMessage().contains("reports no process version"), exception.getMessage());
      assertNull(persistence.aggregates.get("4711").processedBy, "no handler ran");

    }

  }

  @Nested
  @DisplayName("Asynchronous completion")
  class AsynchronousCompletion {

    @Test
    @DisplayName("A method declaring @TaskId says its task stays open")
    public void aTaskIdMethodCompletesAsynchronously() {

      assertTrue(registry.workflowTaskCompletesAsynchronously(MODULE, PROCESS, "asyncTask"));

    }

    @Test
    @DisplayName("A method without @TaskId completes its task on return")
    public void aMethodWithoutTaskIdCompletesOnReturn() {

      assertFalse(registry.workflowTaskCompletesAsynchronously(MODULE, PROCESS, "doSomething"));
      assertFalse(registry.workflowTaskCompletesAsynchronously(MODULE, PROCESS, "explicitDefinition"));
      assertFalse(registry.workflowTaskCompletesAsynchronously(MODULE, PROCESS, "Activity_4711"));

    }

    @Test
    @DisplayName("An element no method serves is not asynchronous either")
    public void anUnknownElementIsNotAsynchronous() {

      assertFalse(registry.workflowTaskCompletesAsynchronously(MODULE, PROCESS, "unknownTask"));
      assertFalse(registry.workflowTaskCompletesAsynchronously(MODULE, "UnknownProcess", "asyncTask"));

    }

    @Test
    @DisplayName("One of several methods keeping the task open is enough")
    public void oneAsynchronousMethodDecidesForTheElement() {

      registry.registerWorkflowService(
          MODULE,
          "MixedAsyncProcess",
          MixedAsyncService.class,
          MixedAsyncService::new,
          beans::get,
          createProcessService());

      assertTrue(registry.workflowTaskCompletesAsynchronously(MODULE, "MixedAsyncProcess", "mixedVersioned"));

    }

  }

  /**
   * Two methods serving ONE BPMN element in different process versions, each reading a
   * variable of its own - what a subscription serving that element has to bring along.
   */
  static class ParameterizedVersionsService {

    @WorkflowTask(taskDefinition = "parameterized", version = "1")
    public void firstVersion(
        final Aggregate aggregate,
        @TaskParam("ratingProvider") final String ratingProvider) {

      aggregate.parameterValue = ratingProvider;

    }

    @WorkflowTask(taskDefinition = "parameterized", version = ">1")
    public void laterVersions(
        final Aggregate aggregate,
        @TaskParam("rating") final String rating,
        @TaskParam("ratingProvider") final String ratingProvider) {

      aggregate.parameterValue = rating + ratingProvider;

    }

  }

  @Nested
  @DisplayName("Declared task parameters")
  class DeclaredTaskParameters {

    @Test
    @DisplayName("The names of a method's @TaskParam parameters are reported to the adapter")
    public void theDeclaredNamesAreReported() {

      assertEquals(
          List.of("status"),
          List.copyOf(registry.taskParameterNames(MODULE, PROCESS, "withBindings")),
          "the adapter cannot see what a method reads - the core scanned it while wiring");

    }

    @Test
    @DisplayName("A method reading nothing but its aggregate declares no parameter")
    public void aMethodWithoutTaskParamDeclaresNothing() {

      assertTrue(registry.taskParameterNames(MODULE, PROCESS, "doSomething").isEmpty());

    }

    @Test
    @DisplayName("A method wired by activity ID is found by that ID")
    public void anActivityIdWiringIsAnsweredToo() {

      assertTrue(registry.taskParameterNames(MODULE, PROCESS, "Activity_4711").isEmpty());
      assertTrue(registry.taskParameterNames(MODULE, PROCESS, "unknownTask").isEmpty());
      assertTrue(registry.taskParameterNames(MODULE, "UnknownProcess", "withBindings").isEmpty());

    }

    @Test
    @DisplayName("Several methods serving one element contribute the UNION of their parameters")
    public void theUnionOfEveryMethodServingTheElement() {

      registry.registerWorkflowService(
          MODULE,
          "ParameterizedProcess",
          ParameterizedVersionsService.class,
          ParameterizedVersionsService::new,
          beans::get,
          createProcessService());

      assertEquals(
          List.of("rating", "ratingProvider"),
          List.copyOf(registry.taskParameterNames(MODULE, "ParameterizedProcess", "parameterized")),
          "whichever version is delivered has to find its variable, and the list is sorted so a "
              + "subscription naming it stays the same across restarts");

    }

    @Test
    @DisplayName("An adapter not implementing the SPI method sees the previous behaviour")
    public void theDefaultAnswersNothing() {

      // the wiring interface, which is where the parameter names live since the SPI was
      // split into wiring-time and runtime duties
      final var untouched = new io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring() {

        @Override
        public void validateTaskWiring(
            final String workflowModuleId,
            final String bpmnProcessId,
            final java.util.Collection<BpmnTaskSpec> tasks) {
        }

        @Override
        public void validateNoUnwiredWorkflowTaskMethods(
            final String workflowModuleId) {
        }

        @Override
        public String resolveWorkflowAggregateIdName(
            final String workflowModuleId,
            final String bpmnProcessId) {

          return null;

        }

      };

      assertTrue(
          untouched.taskParameterNames(MODULE, PROCESS, "withBindings").isEmpty(),
          "the SPI method is additive: an adapter which never heard of it keeps the behaviour it had");

    }

  }

  @Nested
  @DisplayName("Wiring validation")
  class WiringValidation {

    @Test
    @DisplayName("A complete wiring passes silently")
    public void completeWiringPasses() {

      registry.validateTaskWiring(
          MODULE,
          PROCESS,
          List.of(
              new BpmnTaskSpec("Activity_1", "doSomething"),
              new BpmnTaskSpec("Activity_2", "explicitDefinition"),
              new BpmnTaskSpec("Activity_4711", "somethingElse"),
              new BpmnTaskSpec("Activity_3", "fails"),
              new BpmnTaskSpec("Activity_5", "bpmnError"),
              new BpmnTaskSpec("Activity_6", "asyncTask"),
              new BpmnTaskSpec("Activity_7", "withBindings"),
              new BpmnTaskSpec("Activity_8", "withResolver")));

    }

    @Test
    @DisplayName("A BPMN task without handler fails naming the task and the fix")
    public void unmatchedTaskFails() {

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> registry.validateTaskWiring(
              MODULE,
              PROCESS,
              List.of(new BpmnTaskSpec("Activity_9", "notImplemented"))));

      final var message = exception.getMessage();
      assertTrue(message.contains("'Activity_9'"));
      assertTrue(message.contains("'notImplemented'"));
      assertTrue(message.contains("@WorkflowTask(taskDefinition = \"notImplemented\")"));
      assertTrue(message.contains(SampleService.class.getName()));

    }

    @Test
    @DisplayName("A handler matching no task of ANY wired process fails the per-module check")
    public void unmatchedHandlerFailsPerModule() {

      // wiring the process without 'withResolver' marks all other methods wired
      registry.validateTaskWiring(
          MODULE,
          PROCESS,
          List.of(
              new BpmnTaskSpec("Activity_1", "doSomething"),
              new BpmnTaskSpec("Activity_2", "explicitDefinition"),
              new BpmnTaskSpec("Activity_4711", "x"),
              new BpmnTaskSpec("Activity_3", "fails"),
              new BpmnTaskSpec("Activity_5", "bpmnError"),
              new BpmnTaskSpec("Activity_6", "asyncTask"),
              new BpmnTaskSpec("Activity_7", "withBindings")));

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> registry.validateNoUnwiredWorkflowTaskMethods(MODULE));

      final var message = exception.getMessage();
      assertTrue(message.contains("withResolver"));
      assertTrue(message.contains("fix the annotation"));

    }

    @Test
    @DisplayName("A handler wired in ONE of several processes passes the per-module check")
    public void handlerWiredInAnotherProcessPasses() {

      // 'withResolver' matches in a second process of the module - the method is
      // legitimate although the first process does not use it
      registry.validateTaskWiring(
          MODULE,
          PROCESS,
          List.of(
              new BpmnTaskSpec("Activity_1", "doSomething"),
              new BpmnTaskSpec("Activity_2", "explicitDefinition"),
              new BpmnTaskSpec("Activity_4711", "x"),
              new BpmnTaskSpec("Activity_3", "fails"),
              new BpmnTaskSpec("Activity_5", "bpmnError"),
              new BpmnTaskSpec("Activity_6", "asyncTask"),
              new BpmnTaskSpec("Activity_7", "withBindings")));
      registry.registerWorkflowService(
          MODULE,
          "SecondProcess",
          SampleService.class,
          () -> serviceBean,
          beans::get,
          createProcessService());
      registry.validateTaskWiring(
          MODULE,
          "SecondProcess",
          List.of(
              new BpmnTaskSpec("Activity_1", "doSomething"),
              new BpmnTaskSpec("Activity_2", "explicitDefinition"),
              new BpmnTaskSpec("Activity_4711", "x"),
              new BpmnTaskSpec("Activity_3", "fails"),
              new BpmnTaskSpec("Activity_5", "bpmnError"),
              new BpmnTaskSpec("Activity_6", "asyncTask"),
              new BpmnTaskSpec("Activity_7", "withBindings"),
              new BpmnTaskSpec("Activity_8", "withResolver")));

      registry.validateNoUnwiredWorkflowTaskMethods(MODULE);

    }

    @Test
    @DisplayName("A process no @WorkflowService claims is collected for the deployment's report")
    public void noServiceRegisteredIsCollectedRatherThanThrown() {

      // a BPMN file travels to the BPMS as a whole, so a process drawn next to the one
      // the application asked for arrives here - demanding methods for it would end the
      // boot over a model somebody else owns
      registry.validateTaskWiring(
          MODULE,
          "UnclaimedProcess",
          List.of(new BpmnTaskSpec("Activity_1", "someTask")));

      assertEquals(
          List.of("UnclaimedProcess"),
          List.copyOf(registry.bpmnProcessesWithoutWorkflowService(MODULE)),
          "the deployment says once per module what such a process costs");

    }

    @Test
    @DisplayName("The same unclaimed process wired by a second adapter is collected once")
    public void anUnclaimedProcessOfTwoAdaptersIsCollectedOnce() {

      registry.validateTaskWiring(MODULE, "UnclaimedProcess", List.of());
      registry.validateTaskWiring(MODULE, "UnclaimedProcess", List.of());

      assertEquals(
          List.of("UnclaimedProcess"),
          List.copyOf(registry.bpmnProcessesWithoutWorkflowService(MODULE)));

    }

    @Test
    @DisplayName("A claimed process is not collected, and neither is an unclaimed one of another module")
    public void onlyUnclaimedProcessesOfTheModuleAreCollected() {

      registry.validateTaskWiring(
          MODULE,
          PROCESS,
          List.of(new BpmnTaskSpec("Activity_1", "doSomething")));
      registry.validateTaskWiring("other-module", "UnclaimedProcess", List.of());

      assertTrue(
          registry.bpmnProcessesWithoutWorkflowService(MODULE).isEmpty(),
          "the module's only wired process is served");
      assertEquals(
          List.of("UnclaimedProcess"),
          List.copyOf(registry.bpmnProcessesWithoutWorkflowService("other-module")));

    }

    @Test
    @DisplayName("An unclaimed process leaves the reverse check as loud as it was")
    public void anUnclaimedProcessDoesNotSilenceTheReverseCheck() {

      // the module's served process is wired without 'withResolver' - and the
      // unclaimed process next to it must not turn that orphan method into a pass
      registry.validateTaskWiring(
          MODULE,
          PROCESS,
          List.of(
              new BpmnTaskSpec("Activity_1", "doSomething"),
              new BpmnTaskSpec("Activity_2", "explicitDefinition"),
              new BpmnTaskSpec("Activity_4711", "x"),
              new BpmnTaskSpec("Activity_3", "fails"),
              new BpmnTaskSpec("Activity_5", "bpmnError"),
              new BpmnTaskSpec("Activity_6", "asyncTask"),
              new BpmnTaskSpec("Activity_7", "withBindings")));
      registry.validateTaskWiring(
          MODULE,
          "UnclaimedProcess",
          List.of(new BpmnTaskSpec("Activity_9", "withResolver")));

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> registry.validateNoUnwiredWorkflowTaskMethods(MODULE));

      assertTrue(exception.getMessage().contains("withResolver"), exception.getMessage());

    }

  }

  @Test
  @DisplayName("TaskEvent.Event defaults to CREATED and @TaskId to null if the context does not supply them")
  public void contextDefaults() {

    final var context = context("asyncTask");
    assertNull(context.getTaskId());
    assertEquals(TaskEvent.Event.CREATED, context.getTaskEvent());

    final var outcome = registry.invokeWorkflowTask(MODULE, PROCESS, context);
    // a @TaskId method stays open even if the BPMS supplies no task ID
    assertEquals(WorkflowTaskOutcome.Kind.COMPLETION_PENDING, outcome.kind());
    assertNull(persistence.aggregates.get("4711").taskId);

  }

  /**
   * A second aggregate class - used where a test needs a process working on an aggregate
   * of its own.
   */
  public static class GetterAggregate {

    private String id;

  }

  /**
   * An aggregate whose getters carry the sync model the check reads: what a BPMN
   * expression may read is what the aggregate shares.
   */
  @io.vanillabp.spi.service.SyncWithBPMS
  public static class SharingAggregate {

    private String id;

    /**
     * A field without a getter: VanillaBP 1 resolved it in expressions, the sync model
     * does not - which the check has to say (migration).
     */
    private String riskClass;

    public boolean isApprovable() {
      return true;
    }

    /**
     * An isX() method returning something other than boolean: version 1 read it, the
     * JavaBean rule does not (migration).
     */
    public String isRiskLevel() {
      return "high";
    }

    @io.vanillabp.spi.service.NoSyncWithBPMS
    public String getInternalNote() {
      return "not for the engine";
    }

  }

  @Test
  @DisplayName("An expression reading an unshared attribute is reported, an unknown name is not")
  public void unsharedAggregateProperties() {

    final var sharingPersistence = new AggregatePersistenceAware<SharingAggregate>() {

      @Override
      public Class<SharingAggregate> getAggregateClass() {
        return SharingAggregate.class;
      }

      @Override
      public SharingAggregate save(
          final SharingAggregate aggregate) {
        return aggregate;
      }

      @Override
      public Object getAggregateId(
          final SharingAggregate aggregate) {
        return aggregate.id;
      }

      @Override
      public Class<?> getAggregateIdType() {
        return String.class;
      }

      @Override
      public SharingAggregate loadById(
          final Object aggregateId) {
        return new SharingAggregate();
      }

    };
    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of("test-adapter", AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of("test-adapter"))
        .build();
    properties.validateAndLink();
    final var registryWithSync = new WorkflowTaskRegistry(
        transactionRunner, new io.vanillabp.integration.adapter.migration.sync.AggregateSyncSupport());
    registryWithSync.registerWorkflowService(
        MODULE,
        "SharingProcess",
        SharingAggregate.class,
        SharingAggregate::new,
        beans::get,
        MigrationProcessService
            .forBpmnProcess(MODULE, "SharingProcess", SharingAggregate.class)
            .properties(properties)
            .aggregatePersistence(sharingPersistence)
            .processServices(List.of(
                new NoOpProcessService<SharingAggregate>()))
            .build());

    // the attribute the application excluded is reported, the shared one is not, and a
    // name which is no attribute at all is none of this check's business (the model may
    // well provide that variable itself)
    assertEquals(
        List.of("internalNote"),
        registryWithSync.unsharedWorkflowAggregateProperties(
            MODULE,
            "SharingProcess",
            List.of("approvable", "internalNote", "somethingTheModelProvides"),
            io.vanillabp.integration.adapter.spi.AggregateSyncMode.FULL));

    // migration: what VanillaBP 1 resolved and the sync model cannot share is
    // reported as well - a field without a getter, and an isX() returning non-boolean
    assertEquals(
        List.of("riskClass", "riskLevel"),
        registryWithSync.unsharedWorkflowAggregateProperties(
            MODULE,
            "SharingProcess",
            List.of("riskClass", "riskLevel", "approvable"),
            io.vanillabp.integration.adapter.spi.AggregateSyncMode.FULL));

    // the class states its own mode, so the adapter's default changes nothing here -
    // what the application said wins either way
    assertEquals(
        List.of("internalNote"),
        registryWithSync.unsharedWorkflowAggregateProperties(
            MODULE,
            "SharingProcess",
            List.of("approvable", "internalNote"),
            io.vanillabp.integration.adapter.spi.AggregateSyncMode.NONE));

    // an unknown process yields nothing rather than a guess
    assertEquals(
        List.of(),
        registryWithSync.unsharedWorkflowAggregateProperties(
            MODULE,
            "NoSuchProcess",
            List.of("internalNote"),
            io.vanillabp.integration.adapter.spi.AggregateSyncMode.FULL));

  }

  static class NoOpProcessService<T> implements MigratableProcessService<T> {

    @Override
    public String getAdapterId() {
      return "test-adapter";
    }

    @Override
    public java.util.Map<io.vanillabp.integration.spi.PhaseOperation, io.vanillabp.integration.adapter.spi.PhaseOperationHandler<T>> phaseOperations() {
      return io.vanillabp.migration.test.TestPhaseOperations.doingNothing();
    }

    @Override
    public WorkflowAwareness awarenessOfTask(
        final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId) {
      return WorkflowAwareness.UNKNOWN_TO_BPMS;
    }

    @Override
    public WorkflowAwareness awarenessOfWorkflow(
        final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
        final io.vanillabp.integration.spi.AggregatePersistenceAware<T> aggregatePersistence,
        final Object workflowAggregateId) {
      return WorkflowAwareness.UNKNOWN_TO_BPMS;
    }

    @Override
    public io.vanillabp.integration.adapter.spi.WorkflowAwareness awarenessOfUserTask(
        final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId) {
      return io.vanillabp.integration.adapter.spi.WorkflowAwareness.UNKNOWN_TO_BPMS;
    }

  }

  @Test
  @DisplayName("An unsupported version specification fails at registration")
  public void unsupportedVersionSpecFails() {

    class BadVersionService {

      @WorkflowTask(version = ">")
      public void badVersion(
          final Aggregate aggregate) {
      }

    }

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> registry.registerWorkflowService(
            MODULE,
            "BadVersionProcess",
            BadVersionService.class,
            BadVersionService::new,
            beans::get,
            createProcessService()));

    assertTrue(exception.getMessage().contains("Supported formats"));

    class BlankVersionService {

      @WorkflowTask(version = "1 - 3")
      public void blankVersion(
          final Aggregate aggregate) {
      }

    }

    final var blank = assertThrows(
        IllegalStateException.class,
        () -> registry.registerWorkflowService(
            MODULE,
            "BlankVersionProcess",
            BlankVersionService.class,
            BlankVersionService::new,
            beans::get,
            createProcessService()));

    assertTrue(blank.getMessage().contains("blank"));

  }

  @Test
  @DisplayName("A non-numeric version specification is a version tag, not a defect")
  public void versionTagSpecIsAccepted() {

    class TaggedService {

      @WorkflowTask(taskDefinition = "tagged", version = "release-2024")
      public void tagged(
          final Aggregate aggregate) {
      }

    }

    // a version tag cannot be validated at registration time: no BPMS was asked yet,
    // and the tagged version may even be deployed by this very boot
    registry.registerWorkflowService(
        MODULE,
        "TaggedProcess",
        TaggedService.class,
        TaggedService::new,
        beans::get,
        createProcessService());

  }

  /**
   * The registry is the seam through which an adapter holding no
   * aggregate (a task worker of a remote BPMS completing a job) obtains the values
   * shared with the BPMS - and the place where the sync model of every registered
   * workflow-aggregate class is validated at STARTUP.
   */
  @Nested
  @DisplayName("Processes sharing a workflow aggregate")
  class SharedWorkflowAggregates {

    /**
     * Registers a second BPMN process of the module on a workflow aggregate of its
     * own - the "reuse" case, where a called process must NOT be handed the caller's
     * identity.
     */
    private void registerProcessOfAnotherAggregate(
        final String bpmnProcessId) {

      final var persistenceOfOtherAggregate = new AggregatePersistenceAware<GetterAggregate>() {

        @Override
        public Class<GetterAggregate> getAggregateClass() {
          return GetterAggregate.class;
        }

        @Override
        public Object getAggregateId(
            final GetterAggregate aggregate) {
          return aggregate.id;
        }

      };
      final var properties = MigrationAdapterProperties
          .builder()
          .adapters(Map.of("test-adapter", AdapterConfigProperties.ofType("dummy")))
          .prioritizedAdapters(List.of("test-adapter"))
          .build();
      properties.validateAndLink();
      registry.registerWorkflowService(
          MODULE,
          bpmnProcessId,
          GetterAggregate.class, // no @WorkflowTask methods - registration is fine
          GetterAggregate::new,
          beans::get,
          MigrationProcessService
              .forBpmnProcess(MODULE, bpmnProcessId, GetterAggregate.class)
              .properties(properties)
              .aggregatePersistence(persistenceOfOtherAggregate)
              .processServices(List.of(
                  new NoOpProcessService<GetterAggregate>()))
              .build());

    }

    @Test
    @DisplayName("Two processes of one aggregate share it, a process with its own does not")
    public void aggregatesAreComparedByClass() {

      // a secondary process of the same aggregate: the class declaring it named the
      // same aggregate, which is what the adapter asks about a call activity
      registry.registerWorkflowService(
          MODULE,
          "SecondaryProcess",
          SampleService.class,
          () -> serviceBean,
          beans::get,
          createProcessService());
      registerProcessOfAnotherAggregate("ProcessOfItsOwn");

      assertTrue(registry.workflowsShareTheWorkflowAggregate(MODULE, PROCESS, "SecondaryProcess"));
      assertTrue(registry.workflowsShareTheWorkflowAggregate(MODULE, "SecondaryProcess", PROCESS));
      assertFalse(registry.workflowsShareTheWorkflowAggregate(MODULE, PROCESS, "ProcessOfItsOwn"));

    }

    @Test
    @DisplayName("A process nobody declared is not known to share anything")
    public void unknownProcessesShareNothing() {

      assertFalse(registry.workflowsShareTheWorkflowAggregate(MODULE, PROCESS, "NeverDeclared"));
      assertFalse(registry.workflowsShareTheWorkflowAggregate(MODULE, "NeverDeclared", PROCESS));
      assertFalse(registry.workflowsShareTheWorkflowAggregate("another-module", PROCESS, PROCESS));

    }

  }

  @Nested
  @DisplayName("Aggregate sync")
  class AggregateSync {

    private WorkflowTaskRegistry registryWithSync() {

      final var withSync = new WorkflowTaskRegistry(
          transactionRunner, new io.vanillabp.integration.adapter.migration.sync.AggregateSyncSupport());
      withSync.registerWorkflowService(
          MODULE,
          PROCESS,
          SampleService.class,
          () -> serviceBean,
          beans::get,
          createProcessService());
      return withSync;

    }

    @Test
    @DisplayName("An embedded engine reads the shared values in the CALLER's transaction")
    public void sharedValuesForAnEmbeddedEngineAreReadInTheCurrentTransaction() {

      transactionRunner.requireNewUsed = false;
      transactionRunner.inCurrentUsed = false;

      final var values = registryWithSync().syncedWorkflowAggregateValuesInCurrentTransaction(
          MODULE,
          PROCESS,
          "4711",
          io.vanillabp.integration.adapter.spi.AggregateSyncMode.FULL);

      // the engine completes the task in the transaction the handler ran in, so a new
      // one would read the state before the handler - or wait for its row
      assertTrue(transactionRunner.inCurrentUsed, "the values were not read in the caller's transaction");
      assertFalse(transactionRunner.requireNewUsed, "a new transaction was opened although one is running");
      assertEquals("4711", values.get("id"));

    }

    @Test
    @DisplayName("The shared values are read in an OWN transaction, the ID variable is not among them")
    public void sharedValuesAreReadInAnOwnTransaction() {

      transactionRunner.requireNewUsed = false;

      final var values = registryWithSync().syncedWorkflowAggregateValues(
          MODULE,
          PROCESS,
          "4711",
          io.vanillabp.integration.adapter.spi.AggregateSyncMode.FULL);

      // 'parameterValue' is annotated @NoSyncWithBPMS, which derives opt-out for
      // the whole aggregate class
      assertEquals("4711", values.get("id"));
      assertFalse(values.containsKey("parameterValue"), "@NoSyncWithBPMS excludes an attribute");
      assertTrue(
          transactionRunner.requireNewUsed,
          "the task's transaction is committed - the aggregate has to be loaded in a new one");

    }

    @Test
    @DisplayName("Without a sync model nothing is shared")
    public void withoutASyncModelNothingIsShared() {

      assertEquals(
          Map.of(),
          registry.syncedWorkflowAggregateValues(
              MODULE, PROCESS, "4711", io.vanillabp.integration.adapter.spi.AggregateSyncMode.FULL));

    }

    @Test
    @DisplayName("An unknown BPMN process, a missing aggregate and a failing load never break the completion")
    public void failuresAreLoggedAndAnsweredEmpty() {

      final var withSync = registryWithSync();

      assertEquals(
          Map.of(),
          withSync.syncedWorkflowAggregateValues(
              MODULE, "UnknownProcess", "4711", io.vanillabp.integration.adapter.spi.AggregateSyncMode.FULL),
          "an unknown BPMN process is reported, not thrown");
      assertEquals(
          Map.of(),
          withSync.syncedWorkflowAggregateValues(
              MODULE, PROCESS, "0815", io.vanillabp.integration.adapter.spi.AggregateSyncMode.FULL),
          "a missing aggregate is reported, not thrown");

      persistence.failLoad = true;
      try {
        assertEquals(
            Map.of(),
            withSync.syncedWorkflowAggregateValues(
                MODULE, PROCESS, "4711", io.vanillabp.integration.adapter.spi.AggregateSyncMode.FULL),
            "a failing load is reported, not thrown");
      } finally {
        persistence.failLoad = false;
      }

    }

  }

  /**
   * The migration fallback for an EMBEDDED BPMS: it resolves BPMN expressions
   * against the workflow aggregate while its own transaction runs. Version 1 resolved
   * an attribute without a getter as well, so the order is getter, boolean getter,
   * field - and the field is looked up along the class hierarchy, because a V1
   * aggregate typically inherits from a base entity. The two methods are deprecated
   * for removal, and the Camunda 7 adapter's expression resolver calls them today.
   */
  @Nested
  @DisplayName("Attribute resolution for an embedded BPMS")
  class AttributeResolution {

    @Test
    @DisplayName("An attribute is announced through its getter, its boolean getter or its field")
    @SuppressWarnings("removal") // the fallback under test, gone in 2.1
    public void anAttributeIsAnnouncedThroughGetterBooleanGetterOrField() {

      assertTrue(registry.workflowAggregateHasProperty(MODULE, PROCESS, "processedBy"), "getter");
      assertTrue(registry.workflowAggregateHasProperty(MODULE, PROCESS, "urgent"), "boolean getter");
      assertTrue(registry.workflowAggregateHasProperty(MODULE, PROCESS, "taskId"), "field without getter");
      assertTrue(registry.workflowAggregateHasProperty(MODULE, PROCESS, "tenantId"), "field of the base class");
      assertFalse(registry.workflowAggregateHasProperty(MODULE, PROCESS, "somethingElse"));
      assertFalse(registry.workflowAggregateHasProperty(MODULE, PROCESS, ""));
      assertFalse(registry.workflowAggregateHasProperty(MODULE, PROCESS, null));

    }

    @Test
    @DisplayName("An unknown BPMN process announces no attribute and reads none, instead of failing the expression")
    @SuppressWarnings("removal") // the fallback under test, gone in 2.1
    public void anUnknownProcessAnnouncesNoAttribute() {

      // the expression may name something entirely unrelated to VanillaBP - saying
      // "not mine" lets the engine resolve it elsewhere
      assertFalse(registry.workflowAggregateHasProperty(MODULE, "NoSuchProcess", "processedBy"));
      assertNull(registry.resolveWorkflowAggregateProperty(MODULE, "NoSuchProcess", "4711", "processedBy"));

    }

    @Test
    @DisplayName("The value comes from the getter, the boolean getter or the field - the base class included")
    @SuppressWarnings("removal") // the fallback under test, gone in 2.1
    public void theValueIsReadInTheDocumentedOrder() {

      final var aggregate = persistence.aggregates.get("4711");
      aggregate.processedBy = "read by getter";
      aggregate.urgent = true;
      aggregate.taskId = "Task_1";
      aggregate.tenantId = "tenant-4711";

      assertEquals("read by getter", registry.resolveWorkflowAggregateProperty(MODULE, PROCESS, "4711",
          "processedBy"));
      assertEquals(Boolean.TRUE, registry.resolveWorkflowAggregateProperty(MODULE, PROCESS, "4711", "urgent"));
      assertEquals("Task_1", registry.resolveWorkflowAggregateProperty(MODULE, PROCESS, "4711", "taskId"));
      // announcing an inherited attribute and then reading it as null is the silent
      // half-failure this asserts against: both steps walk the class hierarchy
      assertEquals("tenant-4711", registry.resolveWorkflowAggregateProperty(MODULE, PROCESS, "4711", "tenantId"));

    }

    @Test
    @DisplayName("An unknown attribute and a missing aggregate read as null")
    @SuppressWarnings("removal") // the fallback under test, gone in 2.1
    public void anUnknownAttributeAndAMissingAggregateReadAsNull() {

      assertNull(registry.resolveWorkflowAggregateProperty(MODULE, PROCESS, "4711", "somethingElse"));
      assertNull(registry.resolveWorkflowAggregateProperty(MODULE, PROCESS, "0815", "processedBy"));

    }

    @Test
    @DisplayName("A getter which throws is reported and read as null, not thrown into the engine transaction")
    @SuppressWarnings("removal") // the fallback under test, gone in 2.1
    public void aThrowingGetterIsReportedAndReadAsNull() {

      final var broken = new BrokenGetterAggregate();
      broken.id = "0816";
      persistence.aggregates.put("0816", broken);

      assertNull(registry.resolveWorkflowAggregateProperty(MODULE, PROCESS, "0816", "boom"));

    }

  }

  /**
   * The three questions an embedded BPMS asks the registry outside a task invocation:
   * whether an element is wired at all, and what the aggregate-ID variable is called.
   * Each of them can be asked for something unknown, and the answer is what a
   * developer sees when a model and the code drift apart.
   */
  @Nested
  @DisplayName("Questions of an embedded BPMS")
  class EmbeddedBpmsQuestions {

    @Test
    @DisplayName("A wired element is recognised by task definition and by activity ID")
    public void aWiredElementIsRecognisedByBothNames() {

      assertTrue(registry.workflowTaskHandlerExists(MODULE, PROCESS, "doSomething"));
      assertTrue(registry.workflowTaskHandlerExists(MODULE, PROCESS, "explicitDefinition"));
      assertTrue(registry.workflowTaskHandlerExists(MODULE, PROCESS, "Activity_4711"));
      assertFalse(registry.workflowTaskHandlerExists(MODULE, PROCESS, "noSuchElement"));
      assertFalse(registry.workflowTaskHandlerExists(MODULE, "NoSuchProcess", "doSomething"));

    }

    @Test
    @DisplayName("The aggregate-ID variable is named by the aggregate's persistence")
    public void theAggregateIdVariableIsNamedByThePersistence() {

      assertEquals("aggregateId", registry.resolveWorkflowAggregateIdName(MODULE, PROCESS));

    }

    @Test
    @DisplayName("Asking an unknown process for the ID variable names the known processes and the missing @WorkflowService")
    public void askingAnUnknownProcessNamesTheKnownOnes() {

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> registry.resolveWorkflowAggregateIdName(MODULE, "NoSuchProcess"));

      assertTrue(exception.getMessage().contains("@WorkflowService"), exception.getMessage());
      assertTrue(exception.getMessage().contains("NoSuchProcess"), exception.getMessage());
      assertTrue(
          exception.getMessage().contains("'%s' (module '%s')".formatted(PROCESS, MODULE)),
          exception.getMessage());

    }

    @Test
    @DisplayName("A BPMS-initiated start of a process without @WorkflowService names the start event and the known processes")
    public void aStartOfAnUnknownProcessNamesTheStartEvent() {

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> registry.startWorkflowByBpms(MODULE, "NoSuchProcess",
              new io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext() {

                @Override
                public String getStartEventId() {
                  return "TimerStart_1";
                }

                @Override
                public io.vanillabp.spi.service.BpmsStartTrigger.Kind getKind() {
                  return io.vanillabp.spi.service.BpmsStartTrigger.Kind.TIMER;
                }

                @Override
                public java.time.Instant getStartInstant() {
                  return java.time.Instant.EPOCH;
                }

              }));

      assertTrue(exception.getMessage().contains("TimerStart_1"), exception.getMessage());
      assertTrue(exception.getMessage().contains("@WorkflowService"), exception.getMessage());
      assertTrue(
          exception.getMessage().contains("'%s' (module '%s')".formatted(PROCESS, MODULE)),
          exception.getMessage());

    }

  }

}
