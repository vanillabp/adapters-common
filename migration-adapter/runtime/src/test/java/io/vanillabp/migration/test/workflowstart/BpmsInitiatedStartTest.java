package io.vanillabp.migration.test.workflowstart;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmsStartTrigger;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowStartedByBpms;

/**
 * Unit tests of what a workflow started by the BPMS itself needs: the workflow
 * aggregate has to come into existence, get an ID, receive the process variables the
 * model set and - if the application wants a say - pass through a
 * <code>&#64;WorkflowStartedByBpms</code> method.
 */
@ExtendWith(SuppressOutputExtension.class)
public class BpmsInitiatedStartTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String TIMER_EVENT = "DailyTimer";

  private static final Instant TRIGGER_TIME = Instant.parse("2026-08-12T04:00:00Z");

  public static class Aggregate {

    String id;

    String region;

    int amount;

    public String getId() {
      return id;
    }

    public void setId(
        final String id) {
      this.id = id;
    }

    public String getRegion() {
      return region;
    }

    public void setRegion(
        final String region) {
      this.region = region;
    }

    public int getAmount() {
      return amount;
    }

    public void setAmount(
        final int amount) {
      this.amount = amount;
    }

  }

  /** An aggregate whose ID is assigned by the persistence layer while saving. */
  public static class GeneratedIdAggregate {

    Long id;

    public Long getId() {
      return id;
    }

  }

  /** An aggregate VanillaBP cannot build on its own. */
  public static class NoDefaultConstructorAggregate {

    String id;

    public NoDefaultConstructorAggregate(
        final String id) {
      this.id = id;
    }

    public String getId() {
      return id;
    }

  }

  static class TransactionRunnerStub implements TransactionRunner {

    boolean requireNewUsed = false;

    boolean inCurrentUsed = false;

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
      return false;
    }

  }

  static class InMemoryPersistence<A> implements AggregatePersistenceAware<A> {

    final Map<Object, A> aggregates = new HashMap<>();

    private final Class<A> aggregateClass;

    private final Class<?> idType;

    private final java.util.function.Function<A, Object> idOf;

    private final java.util.function.BiConsumer<A, Object> assignId;

    private final AtomicLong sequence = new AtomicLong();

    InMemoryPersistence(
        final Class<A> aggregateClass,
        final Class<?> idType,
        final java.util.function.Function<A, Object> idOf,
        final java.util.function.BiConsumer<A, Object> assignId) {
      this.aggregateClass = aggregateClass;
      this.idType = idType;
      this.idOf = idOf;
      this.assignId = assignId;
    }

    @Override
    public Class<A> getAggregateClass() {
      return aggregateClass;
    }

    @Override
    public String getAggregateIdName() {
      return "id";
    }

    @Override
    public Class<?> getAggregateIdType() {
      return idType;
    }

    @Override
    public Object getAggregateId(
        final A aggregate) {
      return idOf.apply(aggregate);
    }

    @Override
    public A save(
        final A aggregate) {
      if (idOf.apply(aggregate) == null) {
        assignId.accept(aggregate, sequence.incrementAndGet());
      }
      aggregates.put(idOf.apply(aggregate), aggregate);
      return aggregate;
    }

    @Override
    public A loadById(
        final Object aggregateId) {
      return aggregates.get(aggregateId);
    }

  }

  static class SimpleService {

    // no @WorkflowStartedByBpms method at all: VanillaBP builds the aggregate

  }

  static class BuildingService {

    @WorkflowStartedByBpms(id = TIMER_EVENT)
    public Aggregate build(
        final BpmsStartTrigger trigger) {

      final var aggregate = new Aggregate();
      aggregate.setId("settlement-"
          + trigger.time());
      aggregate.setRegion("built by "
          + trigger.kind());
      return aggregate;

    }

  }

  static class EnrichingService {

    BpmsStartTrigger seenTrigger;

    @WorkflowStartedByBpms
    public void enrich(
        final Aggregate aggregate,
        final BpmsStartTrigger trigger,
        @TaskParam("region") final String region) {

      seenTrigger = trigger;
      aggregate.setRegion(region.toUpperCase());

    }

  }

  static class FailingService {

    @WorkflowStartedByBpms
    public void fail(
        final Aggregate aggregate) {

      throw new IllegalStateException("no aggregate today");

    }

  }

  static class CheckedExceptionService {

    @WorkflowStartedByBpms
    public void fail(
        final Aggregate aggregate) throws Exception {

      throw new Exception("a checked one");

    }

  }

  static class NullReturningService {

    @WorkflowStartedByBpms
    public Aggregate build() {

      return null;

    }

  }

  static class OldStartEventService {

    @WorkflowStartedByBpms(id = "OldStart")
    public void keptForTheOldModel(
        final Aggregate aggregate) {

    }

  }

  static class OldStartEventOfAVersionNobodyHoldsService {

    @WorkflowStartedByBpms(id = "OldStart", version = "99")
    public void keptForNothing(
        final Aggregate aggregate) {

    }

  }

  static class TwoMethodsForOneStartEventService {

    @WorkflowStartedByBpms(id = TIMER_EVENT)
    public void first(
        final Aggregate aggregate) {

    }

    @WorkflowStartedByBpms(id = TIMER_EVENT)
    public void second(
        final Aggregate aggregate) {

    }

  }

  static class WrongReturnTypeService {

    @WorkflowStartedByBpms
    public String build() {

      return "nope";

    }

  }

  static class UnbindableParameterService {

    @WorkflowStartedByBpms
    public void build(
        final Aggregate aggregate,
        final StringBuilder somethingElse) {

    }

  }

  private <A> MigrationProcessService<A> processService(
      final AggregatePersistenceAware<A> persistence,
      final Class<A> aggregateClass) {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of("test-adapter", AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of("test-adapter"))
        .build();
    properties.validateAndLink();
    final var adapterProcessService = new MigratableProcessService<A>() {

      @Override
      public String getAdapterId() {
        return "test-adapter";
      }

      @Override
      public java.util.Map<io.vanillabp.integration.spi.PhaseOperation, io.vanillabp.integration.adapter.spi.PhaseOperationHandler<A>> phaseOperations() {
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
          final io.vanillabp.integration.spi.AggregatePersistenceAware<A> aggregatePersistence,
          final Object workflowAggregateId) {
        return WorkflowAwareness.UNKNOWN_TO_BPMS;
      }

      @Override
      public WorkflowAwareness awarenessOfUserTask(
          final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
          final Object workflowAggregateId,
          final String taskId) {
        return WorkflowAwareness.UNKNOWN_TO_BPMS;
      }

    };
    return MigrationProcessService
        .forBpmnProcess(MODULE, PROCESS, aggregateClass)
        .properties(properties)
        .aggregatePersistence(persistence)
        .processServices(List.of(adapterProcessService))
        .build();

  }

  private InMemoryPersistence<Aggregate> stringIdPersistence() {

    return new InMemoryPersistence<>(
        Aggregate.class, String.class, aggregate -> aggregate.id, (
            aggregate,
            id) -> aggregate.id = String.valueOf(id));

  }

  private InMemoryPersistence<GeneratedIdAggregate> generatedIdPersistence() {

    return new InMemoryPersistence<>(
        GeneratedIdAggregate.class, Long.class, aggregate -> aggregate.id, (
            aggregate,
            id) -> aggregate.id = Long.valueOf(String.valueOf(id)));

  }

  private WorkflowTaskRegistry registry(
      final TransactionRunner transactionRunner) {

    return new WorkflowTaskRegistry(transactionRunner);

  }

  private BpmsInitiatedStartContext context(
      final BpmsStartTrigger.Kind kind,
      final String startEventId,
      final Map<String, Object> variables) {

    return new BpmsInitiatedStartContext() {

      @Override
      public String getStartEventId() {
        return startEventId;
      }

      @Override
      public BpmsStartTrigger.Kind getKind() {
        return kind;
      }

      @Override
      public Instant getStartInstant() {
        return TRIGGER_TIME;
      }

      @Override
      public String getSignalName() {
        return kind == BpmsStartTrigger.Kind.SIGNAL
            ? "OrderReceived"
            : null;
      }

      @Override
      public Map<String, Object> getVariables() {
        return variables;
      }

    };

  }

  @Test
  @DisplayName("Without any application code the aggregate is built, the timer's time is its ID and the variables land in it")
  public void aggregateIsBuiltFromTheTrigger() {

    final var persistence = stringIdPersistence();
    final var transactionRunner = new TransactionRunnerStub();
    final var testee = registry(transactionRunner);
    testee
        .registerWorkflowService(
            MODULE, PROCESS, SimpleService.class, SimpleService::new, type -> null, processService(
                persistence, Aggregate.class));

    final var result = testee
        .startWorkflowByBpms(
            MODULE,
            PROCESS,
            context(
                BpmsStartTrigger.Kind.TIMER,
                TIMER_EVENT,
                Map.of("region", "north", "amount", 42, "unknownToTheAggregate", "ignored")));

    assertTrue(result.created());
    assertEquals(TRIGGER_TIME.toString(), result.workflowAggregateId());
    assertEquals("id", result.workflowAggregateIdName());
    // the ID variable is what a remote BPMS needs to address the workflow later
    assertEquals(TRIGGER_TIME.toString(), result.variables().get("id"));

    final var aggregate = persistence.aggregates.get(TRIGGER_TIME.toString());
    assertNotNull(aggregate);
    assertEquals("north", aggregate.getRegion());
    // conversion happens on the way in: the model's number becomes the int attribute
    assertEquals(42, aggregate.getAmount());
    // a new transaction, since the notification did not ask to join one
    assertTrue(transactionRunner.requireNewUsed);

  }

  @Test
  @DisplayName("The same timer time reported twice creates nothing twice")
  public void repeatedNotificationCreatesNothingTwice() {

    final var persistence = stringIdPersistence();
    final var testee = registry(new TransactionRunnerStub());
    testee
        .registerWorkflowService(
            MODULE, PROCESS, SimpleService.class, SimpleService::new, type -> null, processService(
                persistence, Aggregate.class));

    testee
        .startWorkflowByBpms(
            MODULE, PROCESS, context(BpmsStartTrigger.Kind.TIMER, TIMER_EVENT, Map.of("region", "north")));
    // business data written after the start must survive a repeated notification
    persistence.aggregates.get(TRIGGER_TIME.toString()).setRegion("changed meanwhile");

    final var second = testee
        .startWorkflowByBpms(
            MODULE, PROCESS, context(BpmsStartTrigger.Kind.TIMER, TIMER_EVENT, Map.of("region", "north")));

    assertFalse(second.created());
    assertEquals(TRIGGER_TIME.toString(), second.workflowAggregateId());
    assertEquals(1, persistence.aggregates.size());
    assertEquals("changed meanwhile", persistence.aggregates.get(TRIGGER_TIME.toString()).getRegion());

  }

  @Test
  @DisplayName("A signal start has no natural identity, so its aggregate gets a generated ID")
  public void signalStartGetsAGeneratedId() {

    final var persistence = stringIdPersistence();
    final var testee = registry(new TransactionRunnerStub());
    testee
        .registerWorkflowService(
            MODULE, PROCESS, SimpleService.class, SimpleService::new, type -> null, processService(
                persistence, Aggregate.class));

    final var first = testee
        .startWorkflowByBpms(
            MODULE, PROCESS, context(BpmsStartTrigger.Kind.SIGNAL, "SignalStart", Map.of()));
    final var second = testee
        .startWorkflowByBpms(
            MODULE, PROCESS, context(BpmsStartTrigger.Kind.SIGNAL, "SignalStart", Map.of()));

    // two workflows started by two broadcasts are two workflows
    assertTrue(first.created());
    assertTrue(second.created());
    assertFalse(first.workflowAggregateId().equals(second.workflowAggregateId()));
    assertFalse(first.workflowAggregateId().equals(TRIGGER_TIME.toString()));
    assertEquals(2, persistence.aggregates.size());

  }

  @Test
  @DisplayName("A timer whose aggregate has a numeric ID uses the trigger time as epoch millis")
  public void numericIdCarriesTheTriggerTime() {

    final var persistence = generatedIdPersistence();
    final var testee = registry(new TransactionRunnerStub());
    testee
        .registerWorkflowService(
            MODULE, PROCESS, SimpleService.class, SimpleService::new, type -> null, processService(
                persistence, GeneratedIdAggregate.class));

    final var result = testee
        .startWorkflowByBpms(
            MODULE, PROCESS, context(BpmsStartTrigger.Kind.TIMER, TIMER_EVENT, Map.of()));

    // a numeric ID can carry the trigger time, so the repetition guard works here
    // as well
    assertEquals(String.valueOf(TRIGGER_TIME.toEpochMilli()), result.workflowAggregateId());
    assertFalse(
        testee
            .startWorkflowByBpms(
                MODULE, PROCESS, context(BpmsStartTrigger.Kind.TIMER, TIMER_EVENT, Map.of()))
            .created());

  }

  @Test
  @DisplayName("Without a value to derive from, the persistence layer assigns the ID while saving")
  public void generatedIdIsTakenFromThePersistence() {

    final var persistence = generatedIdPersistence();
    final var testee = registry(new TransactionRunnerStub());
    testee
        .registerWorkflowService(
            MODULE, PROCESS, SimpleService.class, SimpleService::new, type -> null, processService(
                persistence, GeneratedIdAggregate.class));

    // a signal has no natural identity and a numeric ID must not be invented -
    // saving assigns it, exactly what @GeneratedValue applications rely on
    final var result = testee
        .startWorkflowByBpms(
            MODULE, PROCESS, context(BpmsStartTrigger.Kind.SIGNAL, "SignalStart", Map.of()));

    assertTrue(result.created());
    assertEquals("1", result.workflowAggregateId());
    assertEquals(1, persistence.aggregates.size());

  }

  @Test
  @DisplayName("A method returning the aggregate replaces what VanillaBP built")
  public void buildingMethodWins() {

    final var persistence = stringIdPersistence();
    final var testee = registry(new TransactionRunnerStub());
    testee
        .registerWorkflowService(
            MODULE, PROCESS, BuildingService.class, BuildingService::new, type -> null, processService(
                persistence, Aggregate.class));
    testee
        .validateBpmsInitiatedStarts(
            MODULE,
            PROCESS,
            List.of(BpmsInitiatedStartSpec.of(TIMER_EVENT, BpmsStartTrigger.Kind.TIMER)));

    final var result = testee
        .startWorkflowByBpms(
            MODULE, PROCESS, context(BpmsStartTrigger.Kind.TIMER, TIMER_EVENT, Map.of()));

    assertEquals("settlement-"
        + TRIGGER_TIME, result.workflowAggregateId());
    assertEquals("built by TIMER", persistence.aggregates.get(result.workflowAggregateId()).getRegion());

  }

  @Test
  @DisplayName("A method kept for a declared-only id is judged by the versions the BPMS holds")
  public void aMethodKeptForADeclaredOnlyIdIsExempt() {

    final var testee = registry(new TransactionRunnerStub());
    // registered, but validateTaskWiring never runs for this id: nothing was
    // deployed under it, which is what declaring the old id of a renamed process
    // looks like from in here
    testee
        .registerWorkflowService(
            MODULE, PROCESS, OldStartEventService.class, OldStartEventService::new, type -> null, processService(
                stringIdPersistence(), Aggregate.class));
    testee.registerProcessVersions("test-adapter", MODULE, PROCESS, catalogHolding("1"));

    // the deployed model (of some other generation's shape) does not carry the
    // method's start event - and must not be what the method is judged by, because
    // the BPMS holds a version which does
    assertDoesNotThrow(
        () -> testee
            .validateBpmsInitiatedStarts(
                MODULE,
                PROCESS,
                List.of(BpmsInitiatedStartSpec.of("SomeOtherStart", BpmsStartTrigger.Kind.TIMER))));

  }

  @Test
  @DisplayName("A method of a declared-only id serving NO held version is still reported")
  public void aMethodServingNoHeldVersionOfADeclaredOnlyIdIsReported() {

    final var testee = registry(new TransactionRunnerStub());
    testee
        .registerWorkflowService(
            MODULE,
            PROCESS,
            OldStartEventOfAVersionNobodyHoldsService.class,
            OldStartEventOfAVersionNobodyHoldsService::new,
            type -> null,
            processService(stringIdPersistence(), Aggregate.class));
    testee.registerProcessVersions("test-adapter", MODULE, PROCESS, catalogHolding("1"));

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> testee
            .validateBpmsInitiatedStarts(
                MODULE,
                PROCESS,
                List.of(BpmsInitiatedStartSpec.of("SomeOtherStart", BpmsStartTrigger.Kind.TIMER))));
    assertTrue(failure.getMessage().contains("OldStart"), failure.getMessage());

  }

  /**
   * A catalog answering that the BPMS holds exactly the given versions.
   */
  private static io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog catalogHolding(
      final String... versions) {

    return new io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog() {

      @Override
      public List<io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion> deployedVersionsOf(
          final String workflowModuleId,
          final String bpmnProcessId) {

        return java.util.Arrays
            .stream(versions)
            .map(version -> io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion.of(version, null))
            .toList();

      }

      @Override
      public io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion resolveVersion(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String versionOrVersionTag) {

        return deployedVersionsOf(workflowModuleId, bpmnProcessId)
            .stream()
            .filter(version -> version.version().equals(versionOrVersionTag))
            .findFirst()
            .orElse(null);

      }

    };

  }

  @Test
  @DisplayName("A void method enriches the aggregate VanillaBP built and sees trigger and variables")
  public void enrichingMethodSeesTriggerAndVariables() {

    final var persistence = stringIdPersistence();
    final var service = new EnrichingService();
    final var testee = registry(new TransactionRunnerStub());
    testee
        .registerWorkflowService(
            MODULE, PROCESS, EnrichingService.class, () -> service, type -> null, processService(
                persistence, Aggregate.class));

    final var result = testee
        .startWorkflowByBpms(
            MODULE,
            PROCESS,
            context(BpmsStartTrigger.Kind.SIGNAL, "SignalStart", Map.of("region", "north")));

    assertEquals("NORTH", persistence.aggregates.get(result.workflowAggregateId()).getRegion());
    assertEquals(BpmsStartTrigger.Kind.SIGNAL, service.seenTrigger.kind());
    assertEquals("OrderReceived", service.seenTrigger.signalName());
    assertEquals("SignalStart", service.seenTrigger.startEventId());
    assertEquals(TRIGGER_TIME, service.seenTrigger.time());

  }

  @Test
  @DisplayName("A failing method fails the start - the BPMS retries, no aggregate is left behind")
  public void failingMethodFailsTheStart() {

    final var persistence = stringIdPersistence();
    final var testee = registry(new TransactionRunnerStub());
    testee
        .registerWorkflowService(
            MODULE, PROCESS, FailingService.class, FailingService::new, type -> null, processService(
                persistence, Aggregate.class));

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> testee
            .startWorkflowByBpms(
                MODULE, PROCESS, context(BpmsStartTrigger.Kind.TIMER, TIMER_EVENT, Map.of())));

    assertEquals("no aggregate today", exception.getMessage());
    // the transaction stub propagates instead of committing: nothing was saved
    assertTrue(persistence.aggregates.isEmpty());

  }

  @Test
  @DisplayName("A checked exception of the application is reported naming the method which threw it")
  public void aCheckedExceptionIsReportedNamingTheMethod() {

    final var persistence = stringIdPersistence();
    final var testee = registry(new TransactionRunnerStub());
    testee
        .registerWorkflowService(
            MODULE, PROCESS, CheckedExceptionService.class, CheckedExceptionService::new, type -> null,
            processService(persistence, Aggregate.class));

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> testee
            .startWorkflowByBpms(
                MODULE, PROCESS, context(BpmsStartTrigger.Kind.TIMER, TIMER_EVENT, Map.of())));

    // reflection hands a checked exception over wrapped, and the wrapper says nothing
    // about where it came from - the method's name is what the developer needs
    assertTrue(exception.getMessage().contains(CheckedExceptionService.class.getName()), exception.getMessage());
    assertTrue(exception.getMessage().contains("checked exception"), exception.getMessage());
    assertEquals("a checked one", exception.getCause().getMessage());
    assertTrue(persistence.aggregates.isEmpty());

  }

  @Test
  @DisplayName("An aggregate without a constructor VanillaBP can use fails guiding")
  public void missingDefaultConstructorFailsGuiding() {

    final var persistence = new InMemoryPersistence<>(
        NoDefaultConstructorAggregate.class, String.class, aggregate -> aggregate.id, (
            aggregate,
            id) -> aggregate.id = String.valueOf(id));
    final var testee = registry(new TransactionRunnerStub());
    testee
        .registerWorkflowService(
            MODULE, PROCESS, SimpleService.class, SimpleService::new, type -> null, processService(
                persistence, NoDefaultConstructorAggregate.class));

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> testee
            .startWorkflowByBpms(
                MODULE, PROCESS, context(BpmsStartTrigger.Kind.TIMER, TIMER_EVENT, Map.of())));

    assertTrue(exception.getMessage().contains(NoDefaultConstructorAggregate.class.getName()));
    assertTrue(exception.getMessage().contains("without arguments"));
    assertTrue(exception.getMessage().contains("@WorkflowStartedByBpms"));

  }

  @Test
  @DisplayName("A method for a process the BPMS never starts on its own fails the boot")
  public void methodWithoutSuchStartEventFailsTheBoot() {

    final var testee = registry(new TransactionRunnerStub());
    testee
        .registerWorkflowService(
            MODULE, PROCESS, EnrichingService.class, EnrichingService::new, type -> null, processService(
                stringIdPersistence(), Aggregate.class));

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> testee.validateBpmsInitiatedStarts(MODULE, PROCESS, List.of()));

    assertTrue(exception.getMessage().contains(EnrichingService.class.getName()));
    assertTrue(exception.getMessage().contains(PROCESS));
    assertTrue(exception.getMessage().contains("startWorkflow"));

  }

  @Test
  @DisplayName("A method naming a start event the process does not have fails the boot")
  public void methodNamingAnUnknownStartEventFailsTheBoot() {

    final var testee = registry(new TransactionRunnerStub());
    testee
        .registerWorkflowService(
            MODULE, PROCESS, BuildingService.class, BuildingService::new, type -> null, processService(
                stringIdPersistence(), Aggregate.class));

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> testee
            .validateBpmsInitiatedStarts(
                MODULE,
                PROCESS,
                List.of(BpmsInitiatedStartSpec.of("AnotherTimer", BpmsStartTrigger.Kind.TIMER))));

    assertTrue(exception.getMessage().contains(TIMER_EVENT));
    assertTrue(exception.getMessage().contains("AnotherTimer"));

  }

  @Test
  @DisplayName("A method returning null fails naming the workflow it left without an aggregate")
  public void nullReturningMethodFailsGuiding() {

    final var persistence = stringIdPersistence();
    final var testee = registry(new TransactionRunnerStub());
    testee
        .registerWorkflowService(
            MODULE, PROCESS, NullReturningService.class, NullReturningService::new, type -> null, processService(
                persistence, Aggregate.class));

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> testee
            .startWorkflowByBpms(
                MODULE, PROCESS, context(BpmsStartTrigger.Kind.TIMER, TIMER_EVENT, Map.of())));

    assertTrue(exception.getMessage().contains(NullReturningService.class.getName()));
    assertTrue(exception.getMessage().contains(PROCESS));
    assertTrue(persistence.aggregates.isEmpty());

  }

  @Test
  @DisplayName("Two methods serving the same start event are ambiguous and fail at startup")
  public void twoMethodsForOneStartEventFail() {

    final var testee = registry(new TransactionRunnerStub());

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> testee
            .registerWorkflowService(
                MODULE,
                PROCESS,
                TwoMethodsForOneStartEventService.class,
                TwoMethodsForOneStartEventService::new,
                type -> null,
                processService(stringIdPersistence(), Aggregate.class)));

    assertTrue(exception.getMessage().contains(TIMER_EVENT));
    assertTrue(exception.getMessage().contains(TwoMethodsForOneStartEventService.class.getName()));

  }

  @Test
  @DisplayName("A method returning something else than the aggregate fails at startup")
  public void wrongReturnTypeFailsAtStartup() {

    final var testee = registry(new TransactionRunnerStub());

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> testee
            .registerWorkflowService(
                MODULE,
                PROCESS,
                WrongReturnTypeService.class,
                WrongReturnTypeService::new,
                type -> null,
                processService(stringIdPersistence(), Aggregate.class)));

    assertTrue(exception.getMessage().contains(Aggregate.class.getName()));
    assertTrue(exception.getMessage().contains("java.lang.String"));

  }

  @Test
  @DisplayName("A parameter which is neither the aggregate, the trigger nor a variable fails at startup")
  public void unbindableParameterFailsAtStartup() {

    final var testee = registry(new TransactionRunnerStub());

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> testee
            .registerWorkflowService(
                MODULE,
                PROCESS,
                UnbindableParameterService.class,
                UnbindableParameterService::new,
                type -> null,
                processService(stringIdPersistence(), Aggregate.class)));

    assertTrue(exception.getMessage().contains("@TaskParam"));
    assertTrue(exception.getMessage().contains(BpmsStartTrigger.class.getName()));

  }

}
