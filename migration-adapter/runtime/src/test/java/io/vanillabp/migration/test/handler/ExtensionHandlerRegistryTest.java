package io.vanillabp.migration.test.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import io.vanillabp.integration.extension.spi.handler.CoreHandlerParameter;
import io.vanillabp.integration.extension.spi.handler.HandlerCall;
import io.vanillabp.integration.extension.spi.handler.HandlerContext;
import io.vanillabp.integration.extension.spi.handler.HandlerContract;
import io.vanillabp.integration.extension.spi.handler.HandlerMultiInstance;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.MultiInstanceIndex;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.TaskParam;

/**
 * The edge cases of the handler contracts an extension registers: what is matched, what
 * is refused while the application boots, and what happens to the workflow aggregate
 * around an invocation.
 * <p>
 * The way through is the same one both platforms take - a workflow service registered in
 * the {@link WorkflowTaskRegistry}, a contract registered in the extension handlers it
 * owns - so nothing here is a shortcut around the production path.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ExtensionHandlerRegistryTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String EXTENSION = "sample";

  /**
   * The annotation an extension brings.
   */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  @Repeatable(Notes.class)
  public @interface Note {

    String element() default "";

    /**
     * An attribute the contract cannot describe: what it means is the extension's
     * business, and so is which values it serves.
     */
    String version() default "";

  }

  /**
   * Holds the repetitions of {@link Note}.
   */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface Notes {

    Note[] value();

  }

  /**
   * A second annotation, for the contract which delivers nothing.
   */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface Silent {
  }

  /**
   * The extension's own payload, bound by a binder it contributes.
   */
  public record Payload(String text) {
  }

  /**
   * The workflow aggregate of these tests.
   */
  public static class Aggregate {

    String id;

    String touched;

    public String getId() {

      return id;

    }

    public String getTouched() {

      return touched;

    }

  }

  public static class NotingService {

    @Note(element = "TheTask")
    public String noteOfTheTask(
        final Aggregate aggregate,
        final Payload payload,
        @TaskParam("kind") final String kind) {

      aggregate.touched = "noteOfTheTask";
      return "%s/%s/%s".formatted(aggregate.getId(), payload.text(), kind);

    }

    @Note
    public String byItsName(
        final Payload payload) {

      return "named/%s".formatted(payload.text());

    }

    @Note(element = HandlerContract.EVERY_KEY)
    public String everything(
        final Payload payload) {

      return "every/%s".formatted(payload.text());

    }

  }

  /**
   * A second workflow service, for a second BPMN process of the same workflow module.
   */
  public static class SecondProcessService {

    @Note(element = "TheTask")
    public String noteOfTheTask(
        final Payload payload) {

      return "second-process";

    }

  }

  public static class VersionedNotesService {

    @Note(element = "TheTask", version = "2")
    public String noteOfTheTask(
        final Payload payload) {

      return "versioned";

    }

  }

  public static class TwiceAnnotatedService {

    @Note(element = "TheTask")
    @Note(element = "TheOtherTask")
    public String noteOfBoth(
        final Payload payload) {

      return "both";

    }

  }

  public static class HiddenNoteService {

    @Note(element = "TheTask")
    protected String tooWellHidden(
        final Payload payload) {

      return "nobody reaches this";

    }

  }

  public static class MultiInstanceService {

    @Note(element = "TheTask")
    public String noteOfTheTask(
        @MultiInstanceIndex("TheLoop") final int index) {

      return "index/%d".formatted(index);

    }

  }

  public static class TwoMethodsForOneElementService {

    @Note(element = "TheTask")
    public String first(
        final Payload payload) {

      return "first";

    }

    @Note(element = "TheTask")
    public String second(
        final Payload payload) {

      return "second";

    }

  }

  public static class ReturningWhereNothingIsDeliveredService {

    @Silent
    public String returnsSomething() {

      return "nobody reads this";

    }

  }

  public static class UnbindableParameterService {

    @Note(element = "TheTask")
    public String noteOfTheTask(
        @TaskId final String taskId) {

      return taskId;

    }

  }

  public static class FailingService {

    @Note(element = "TheTask")
    public String noteOfTheTask(
        final Aggregate aggregate) {

      throw new IllegalStateException("no note today");

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

      // a copy, so what a handler changed without being saved never reaches the store
      final var stored = new Aggregate();
      stored.id = aggregate.id;
      stored.touched = aggregate.touched;
      aggregates.put(aggregate.id, stored);
      return aggregate;

    }

    @Override
    public Aggregate loadById(
        final Object aggregateId) {

      final var stored = aggregates.get(aggregateId);
      if (stored == null) {
        return null;
      }
      final var copy = new Aggregate();
      copy.id = stored.id;
      copy.touched = stored.touched;
      return copy;

    }

  }

  private static MigrationProcessService<Aggregate> processService(
      final AggregatePersistenceAware<Aggregate> persistence) {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of("test-adapter", AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of("test-adapter"))
        .build();
    properties.validateAndLink();
    final var adapter = new MigratableProcessService<Aggregate>() {

      @Override
      public String getAdapterId() {

        return "test-adapter";

      }

      @Override
      public Map<io.vanillabp.integration.spi.PhaseOperation, io.vanillabp.integration.adapter.spi.PhaseOperationHandler<Aggregate>> phaseOperations() {

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
          final AggregatePersistenceAware<Aggregate> aggregatePersistence,
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
        .forBpmnProcess(MODULE, PROCESS, Aggregate.class)
        .properties(properties)
        .aggregatePersistence(persistence)
        .processServices(List.of(adapter))
        .build();

  }

  /**
   * The contract of the tests: a payload bound by type, everything the core can bind, and
   * the return value delivered.
   */
  private static HandlerContract noteContract() {

    return HandlerContract
        .of(EXTENSION, Note.class)
        .lookupKeys(annotation -> ((Note) annotation).element().isEmpty()
            ? List.of()
            : List.of(((Note) annotation).element()))
        .coreParameters(
            CoreHandlerParameter.WORKFLOW_AGGREGATE,
            CoreHandlerParameter.TASK_PARAM,
            CoreHandlerParameter.MULTI_INSTANCE)
        .parameterBinder(parameter -> parameter.getType().equals(Payload.class)
            ? Optional.of(HandlerContext::getPayload)
            : Optional.empty())
        .deliversReturnValue()
        .build();

  }

  private static HandlerCall.Builder call(
      final String elementId) {

    return HandlerCall
        .of(Note.class, MODULE, PROCESS)
        .lookupKeys(List.of(elementId))
        .workflowAggregateId("4711")
        .payload(new Payload("hello"));

  }

  private record Fixture(
                         WorkflowTaskRegistry registry,
                         InMemoryPersistence persistence,
                         TransactionRunnerStub transactionRunner) {
  }

  /**
   * Registers a workflow service and a contract, in the order given, and puts one
   * aggregate into the store.
   */
  private static Fixture fixture(
      final Class<?> workflowServiceClass,
      final Supplier<Object> workflowServiceBean,
      final boolean contractFirst) {

    final var transactionRunner = new TransactionRunnerStub();
    final var registry = new WorkflowTaskRegistry(transactionRunner);
    final var persistence = new InMemoryPersistence();
    final var aggregate = new Aggregate();
    aggregate.id = "4711";
    persistence.save(aggregate);

    if (contractFirst) {
      registry.getExtensionHandlers().register(noteContract());
    }
    registry
        .registerWorkflowService(
            MODULE,
            PROCESS,
            workflowServiceClass,
            workflowServiceBean,
            type -> null,
            processService(persistence));
    if (!contractFirst) {
      registry.getExtensionHandlers().register(noteContract());
    }
    return new Fixture(registry, persistence, transactionRunner);

  }

  @Test
  @DisplayName("A method is matched by what its annotation names, and every parameter of the contract is bound")
  public void aMethodIsMatchedByItsAnnotation() {

    final var fixture = fixture(NotingService.class, NotingService::new, true);

    final var returned = fixture
        .registry()
        .getExtensionHandlers()
        .invoke(call("TheTask").variable("kind", "CREATED").build());

    assertEquals("4711/hello/CREATED", returned.orElseThrow());
    // the aggregate was loaded, handed over and saved
    assertEquals("noteOfTheTask", fixture.persistence().aggregates.get("4711").getTouched());
    assertTrue(fixture.transactionRunner().requireNewUsed);

  }

  @Test
  @DisplayName("A contract registered after the workflow services finds the same methods")
  public void aContractMayArriveAfterTheScan() {

    final var fixture = fixture(NotingService.class, NotingService::new, false);

    assertEquals(
        "4711/hello/CREATED",
        fixture
            .registry()
            .getExtensionHandlers()
            .invoke(call("TheTask").variable("kind", "CREATED").build())
            .orElseThrow());

  }

  @Test
  @DisplayName("A method naming no element serves the key its own name is")
  public void aMethodIsMatchedByItsName() {

    final var fixture = fixture(NotingService.class, NotingService::new, true);

    assertEquals(
        "named/hello",
        fixture
            .registry()
            .getExtensionHandlers()
            .invoke(call("byItsName").build())
            .orElseThrow());

  }

  @Test
  @DisplayName("A method naming EVERY_KEY serves what nobody else does")
  public void aMethodMayServeEveryKey() {

    final var fixture = fixture(NotingService.class, NotingService::new, true);

    assertEquals(
        "every/hello",
        fixture
            .registry()
            .getExtensionHandlers()
            .invoke(call("SomethingNobodyNamed").build())
            .orElseThrow());

  }

  @Test
  @DisplayName("A BPMN process no workflow service of this extension serves is answered with nothing")
  public void anUnservedProcessIsNoError() {

    final var fixture = fixture(NotingService.class, NotingService::new, true);
    final var handlers = fixture
        .registry()
        .getExtensionHandlers();

    assertFalse(handlers.hasHandler(Note.class, MODULE, "AnotherProcess", List.of("TheTask")));
    assertTrue(
        handlers
            .invoke(
                HandlerCall
                    .of(Note.class, MODULE, "AnotherProcess")
                    .lookupKeys(List.of("TheTask"))
                    .workflowAggregateId("4711")
                    .payload(new Payload("hello"))
                    .build())
            .isEmpty());

  }

  @Test
  @DisplayName("A call which says so leaves the workflow aggregate unsaved")
  public void aReadingCallDoesNotSaveTheAggregate() {

    final var fixture = fixture(NotingService.class, NotingService::new, true);

    fixture
        .registry()
        .getExtensionHandlers()
        .invoke(call("TheTask").variable("kind", "CREATED").withoutSavingTheWorkflowAggregate().build());

    assertEquals(null, fixture.persistence().aggregates.get("4711").getTouched());

  }

  @Test
  @DisplayName("A call which says so runs in the transaction of its caller")
  public void aCallMayRunInTheCallersTransaction() {

    final var fixture = fixture(NotingService.class, NotingService::new, true);

    fixture
        .registry()
        .getExtensionHandlers()
        .invoke(call("TheTask").variable("kind", "CREATED").inTheCurrentTransaction().build());

    assertTrue(fixture.transactionRunner().inCurrentUsed);
    assertFalse(fixture.transactionRunner().requireNewUsed);

  }

  @Test
  @DisplayName("An aggregate handed in is used instead of one loaded from the store")
  public void anAggregateMayBeHandedIn() {

    final var fixture = fixture(NotingService.class, NotingService::new, true);
    final var built = new Aggregate();
    built.id = "built-by-the-caller";

    final var returned = fixture
        .registry()
        .getExtensionHandlers()
        .invoke(
            HandlerCall
                .of(Note.class, MODULE, PROCESS)
                .lookupKeys(List.of("TheTask"))
                .workflowAggregate(built)
                .payload(new Payload("hello"))
                .variable("kind", "CREATED")
                .build());

    assertEquals("built-by-the-caller/hello/CREATED", returned.orElseThrow());

  }

  @Test
  @DisplayName("The multi-instance context of the invocation reaches the method")
  public void theMultiInstanceContextIsBound() {

    final var transactionRunner = new TransactionRunnerStub();
    final var registry = new WorkflowTaskRegistry(transactionRunner);
    final var persistence = new InMemoryPersistence();
    final var aggregate = new Aggregate();
    aggregate.id = "4711";
    persistence.save(aggregate);
    registry.getExtensionHandlers().register(noteContract());
    registry
        .registerWorkflowService(
            MODULE,
            PROCESS,
            MultiInstanceService.class,
            MultiInstanceService::new,
            type -> null,
            processService(persistence));

    assertEquals(
        "index/2",
        registry
            .getExtensionHandlers()
            .invoke(
                call("TheTask")
                    .multiInstance("TheLoop", new HandlerMultiInstance("item", 2, 5))
                    .build())
            .orElseThrow());

  }

  @Test
  @DisplayName("A workflow aggregate the store does not hold is refused naming the extension")
  public void aMissingAggregateIsRefusedGuiding() {

    final var fixture = fixture(NotingService.class, NotingService::new, true);

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> fixture
            .registry()
            .getExtensionHandlers()
            .invoke(call("TheTask").workflowAggregateId("nobody").build()));
    assertTrue(failure.getMessage().contains(EXTENSION));
    assertTrue(failure.getMessage().contains("nobody"));

  }

  @Test
  @DisplayName("What the method throws reaches the extension unchanged")
  public void whatTheMethodThrowsPropagates() {

    final var fixture = fixture(FailingService.class, FailingService::new, true);

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> fixture
            .registry()
            .getExtensionHandlers()
            .invoke(call("TheTask").build()));
    assertEquals("no note today", failure.getMessage());

  }

  @Test
  @DisplayName("Two methods serving one element end the boot naming both")
  public void twoMethodsForOneElementEndTheBoot() {

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> fixture(TwoMethodsForOneElementService.class, TwoMethodsForOneElementService::new, true));
    assertTrue(failure.getMessage().contains("first"));
    assertTrue(failure.getMessage().contains("second"));
    assertTrue(failure.getMessage().contains(EXTENSION));

  }

  @Test
  @DisplayName("A parameter nothing can bind ends the boot naming what may stand there")
  public void anUnbindableParameterEndsTheBoot() {

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> fixture(UnbindableParameterService.class, UnbindableParameterService::new, true));
    assertTrue(failure.getMessage().contains("noteOfTheTask"));
    assertTrue(failure.getMessage().contains("@TaskParam"));
    assertTrue(failure.getMessage().contains(EXTENSION));

  }

  @Test
  @DisplayName("A method returning something where nothing is delivered ends the boot")
  public void aReturnValueNobodyReadsEndsTheBoot() {

    final var transactionRunner = new TransactionRunnerStub();
    final var registry = new WorkflowTaskRegistry(transactionRunner);
    registry
        .getExtensionHandlers()
        .register(HandlerContract.of(EXTENSION, Silent.class).build());

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> registry
            .registerWorkflowService(
                MODULE,
                PROCESS,
                ReturningWhereNothingIsDeliveredService.class,
                ReturningWhereNothingIsDeliveredService::new,
                type -> null,
                processService(new InMemoryPersistence())));
    assertTrue(failure.getMessage().contains("void"));

  }

  @Test
  @DisplayName("One annotation belongs to one extension")
  public void anAnnotationIsClaimedOnlyOnce() {

    final var registry = new WorkflowTaskRegistry(new TransactionRunnerStub());
    registry.getExtensionHandlers().register(noteContract());

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> registry.getExtensionHandlers().register(noteContract()));
    assertTrue(failure.getMessage().contains("Note"));

  }

  @Test
  @DisplayName("An annotation nobody registered is refused naming the ones which are")
  public void anUnregisteredAnnotationIsRefusedGuiding() {

    final var registry = new WorkflowTaskRegistry(new TransactionRunnerStub());
    registry.getExtensionHandlers().register(noteContract());

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> registry
            .getExtensionHandlers()
            .hasHandler(Silent.class, MODULE, PROCESS, List.of("TheTask")));
    assertTrue(failure.getMessage().contains("@Silent"));
    assertTrue(failure.getMessage().contains("@Note"));

  }

  @Test
  @DisplayName("A contract without an extension id or without an annotation is refused")
  public void aContractHasToNameItsExtensionAndItsAnnotation() {

    assertThrows(IllegalArgumentException.class, () -> HandlerContract.of("  ", Note.class).build());
    assertThrows(IllegalArgumentException.class, () -> HandlerContract.of(EXTENSION, null).build());

  }

  @Test
  @DisplayName("A call naming neither an aggregate nor its id is refused")
  public void aCallHasToNameAnAggregate() {

    assertThrows(
        IllegalArgumentException.class,
        () -> HandlerCall.of(Note.class, MODULE, PROCESS).build());

  }

  @Test
  @DisplayName("The registry says which aggregate a BPMN process works on and which processes a module has")
  public void theRegistryAnswersWhatItKnowsAboutTheApplication() {

    final var registry = new WorkflowTaskRegistry(new TransactionRunnerStub());
    final var persistence = new InMemoryPersistence();
    registry.getExtensionHandlers().register(noteContract());
    registry
        .registerWorkflowService(
            MODULE, PROCESS, NotingService.class, NotingService::new, type -> null, processService(persistence));
    registry
        .registerWorkflowService(
            MODULE,
            "SecondProcess",
            SecondProcessService.class,
            SecondProcessService::new,
            type -> null,
            processService(persistence));

    final var handlers = registry.getExtensionHandlers();

    assertEquals(Optional.of(Aggregate.class), handlers.workflowAggregateOf(MODULE, PROCESS));
    assertEquals(Optional.of(Aggregate.class), handlers.workflowAggregateOf(MODULE, "SecondProcess"));
    assertEquals(List.of(PROCESS, "SecondProcess"), handlers.bpmnProcessesOf(MODULE));

  }

  @Test
  @DisplayName("A process or a workflow module nothing declared is answered with nothing")
  public void whatNobodyDeclaredIsAnsweredWithNothing() {

    final var registry = new WorkflowTaskRegistry(new TransactionRunnerStub());
    registry
        .registerWorkflowService(
            MODULE,
            PROCESS,
            NotingService.class,
            NotingService::new,
            type -> null,
            processService(new InMemoryPersistence()));

    final var handlers = registry.getExtensionHandlers();

    assertEquals(Optional.empty(), handlers.workflowAggregateOf(MODULE, "NobodyDeclaredThis"));
    assertEquals(Optional.empty(), handlers.workflowAggregateOf("other-module", PROCESS));
    assertEquals(List.of(), handlers.bpmnProcessesOf("other-module"));
    // the aggregate is known although this extension has no contract registered at all:
    // the answer is about the application, not about the extension's own methods
    assertEquals(Optional.of(Aggregate.class), handlers.workflowAggregateOf(MODULE, PROCESS));

  }

  @Test
  @DisplayName("What an extension checks about its own annotation ends the boot naming class and method")
  public void anAnnotationTheExtensionRefusesEndsTheBoot() {

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> registryWith(checkingContract(), VersionedNotesService.class, VersionedNotesService::new));

    assertTrue(failure.getMessage().contains(VersionedNotesService.class.getName()), failure.getMessage());
    assertTrue(failure.getMessage().contains("noteOfTheTask"), failure.getMessage());
    assertTrue(failure.getMessage().contains(EXTENSION), failure.getMessage());
    // and what the extension itself said about it
    assertTrue(failure.getMessage().contains("version '2'"), failure.getMessage());

  }

  @Test
  @DisplayName("The check sees every occurrence of a repeatable annotation")
  public void everyOccurrenceIsChecked() {

    final var checked = new java.util.LinkedList<String>();
    final var contract = HandlerContract
        .of(EXTENSION, Note.class)
        .lookupKeys(annotation -> List.of(((Note) annotation).element()))
        .parameterBinder(parameter -> parameter.getType().equals(Payload.class)
            ? Optional.of(HandlerContext::getPayload)
            : Optional.empty())
        .validatingAnnotation((
            annotation,
            method) -> checked
                .add("%s#%s".formatted(((Note) annotation).element(), method.getName())))
        .deliversReturnValue()
        .build();

    registryWith(contract, TwiceAnnotatedService.class, TwiceAnnotatedService::new);

    assertEquals(List.of("TheTask#noteOfBoth", "TheOtherTask#noteOfBoth"), checked);

  }

  @Test
  @DisplayName("A contract without a check is scanned as before")
  public void aContractMayCheckNothing() {

    final var fixture = fixture(NotingService.class, NotingService::new, true);

    assertTrue(
        fixture
            .registry()
            .getExtensionHandlers()
            .hasHandler(Note.class, MODULE, PROCESS, List.of("TheTask")));

  }

  @Test
  @DisplayName("A handler method of an extension which the scan cannot reach is reported like a @WorkflowTask one")
  public void anInvisibleExtensionHandlerIsReported() {

    final var report = io.vanillabp.integration.adapter.migration.workflowtask.HandlerMethodsNobodySees
        .reportFor(HiddenNoteService.class, List.of(Note.class));

    assertTrue(report.contains(HiddenNoteService.class.getName()), report);
    assertTrue(report.contains("the @Note method"), report);
    assertTrue(report.contains("tooWellHidden"), report);
    assertTrue(report.contains("is protected"), report);
    assertTrue(report.contains("Make the method public"), report);
    // and the annotations of VanillaBP's own SPI say nothing about this class
    assertEquals(
        null,
        io.vanillabp.integration.adapter.migration.workflowtask.HandlerMethodsNobodySees
            .reportFor(
                HiddenNoteService.class,
                io.vanillabp.integration.adapter.migration.workflowtask.HandlerMethodsNobodySees.CORE_HANDLER_ANNOTATIONS));

  }

  /**
   * A contract refusing the one attribute it cannot describe - what an extension does
   * with a version, a template path or anything else only it understands.
   */
  private static HandlerContract checkingContract() {

    return HandlerContract
        .of(EXTENSION, Note.class)
        .lookupKeys(annotation -> List.of(((Note) annotation).element()))
        .parameterBinder(parameter -> parameter.getType().equals(Payload.class)
            ? Optional.of(HandlerContext::getPayload)
            : Optional.empty())
        .validatingAnnotation((
            annotation,
            method) -> {
          final var version = ((Note) annotation).version();
          if (!version.isEmpty()) {
            throw new IllegalArgumentException(
                "this extension does not serve version '%s' yet - remove the attribute".formatted(version));
          }
        })
        .deliversReturnValue()
        .build();

  }

  /**
   * A registry holding one contract and one workflow service, registered in that order.
   */
  private static WorkflowTaskRegistry registryWith(
      final HandlerContract contract,
      final Class<?> workflowServiceClass,
      final Supplier<Object> workflowServiceBean) {

    final var registry = new WorkflowTaskRegistry(new TransactionRunnerStub());
    registry.getExtensionHandlers().register(contract);
    registry
        .registerWorkflowService(
            MODULE,
            PROCESS,
            workflowServiceClass,
            workflowServiceBean,
            type -> null,
            processService(new InMemoryPersistence()));
    return registry;

  }

}
