package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.extension.sample.SampleNote;
import io.vanillabp.extension.sample.SampleNoteDetails;
import io.vanillabp.extension.sample.SampleNoteService;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.extension.spi.election.WorkflowElection;
import io.vanillabp.integration.extension.spi.handler.ExtensionHandlers;
import io.vanillabp.integration.test.extension.EveryWorkflowRunsHere;
import io.vanillabp.integration.test.extension.NoteAggregate;
import io.vanillabp.integration.test.extension.NoteAggregatePersistence;
import io.vanillabp.integration.test.extension.NoteTaskWiringSource;
import io.vanillabp.integration.test.extension.NoteWorkflowService;
import io.vanillabp.integration.test.extension.TransactionUsingExtension;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * What an extension gets from VanillaBP on Quarkus: its own annotation runs through the
 * mechanics of <code>&#64;WorkflowTask</code>, its per-aggregate service is injectable
 * with the aggregate as its type argument, the election answers which BPMS holds a
 * workflow, and its settings live below <code>vanillabp.extensions</code>.
 * <p>
 * The same four things the Spring Boot integration proves in its own scenario - measured
 * separately per platform on purpose, because a mechanism working in the core says
 * nothing about a platform ever calling it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ExtensionEnablementTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "NoteProcess";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("extension/application.yaml", "application.yaml")
          .addClass(NoteAggregate.class)
          .addClass(NoteAggregatePersistence.class)
          .addClass(NoteWorkflowService.class)
          .addClass(EveryWorkflowRunsHere.class)
          .addClass(TransactionUsingExtension.class)
          .addClass(NoteTaskWiringSource.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/NoteProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  NoteAggregatePersistence persistence;

  @Inject
  SampleNoteService<NoteAggregate> noteService;

  @Inject
  ExtensionHandlers handlers;

  @Inject
  WorkflowElection election;

  @Inject
  MigrationAdapterProperties properties;

  @Inject
  TransactionUsingExtension transactions;

  @Inject
  jakarta.transaction.UserTransaction userTransaction;

  private NoteAggregate aggregate(
      final String id,
      final String content) {

    final var aggregate = new NoteAggregate();
    aggregate.setId(id);
    aggregate.setContent(content);
    persistence.save(aggregate);
    return aggregate;

  }

  @Test
  @DisplayName("A method of the extension's annotation is invoked with everything its contract allows")
  public void everyParameterKindIsBound() {

    final var aggregate = aggregate("1", "a-loan");

    final var note = noteService
        .noteOf(aggregate, "TheServiceTask", SampleNoteDetails.Kind.CREATED)
        .orElseThrow();

    // the workflow aggregate VanillaBP loaded, the note the extension prefilled, the
    // value of the extension's own annotated parameter, and a process variable.
    // "Servus" rather than "Hello": the workflow module overrides what the extension is
    // configured with globally
    assertEquals("a-loan/Servus TheServiceTask/CREATED/CREATED", note.getTitle());

  }

  @Test
  @DisplayName("A method naming no element serves the key its own name is")
  public void aMethodIsMatchedByItsName() {

    final var aggregate = aggregate("2", "named-by-method");

    final var note = noteService
        .noteOf(aggregate, "endOfTheProcess", SampleNoteDetails.Kind.COMPLETED)
        .orElseThrow();

    assertEquals("the end", note.getTitle());

  }

  @Test
  @DisplayName("An element no method serves is answered with nothing, and that is not an error")
  public void anUnservedElementIsNoError() {

    final var aggregate = aggregate("3", "unserved");

    assertTrue(noteService.noteOf(aggregate, "TheStartEvent", SampleNoteDetails.Kind.CREATED).isEmpty());
    assertFalse(handlers.hasHandler(SampleNote.class, MODULE, PROCESS, List.of("TheStartEvent")));
    assertTrue(handlers.hasHandler(SampleNote.class, MODULE, PROCESS, List.of("TheServiceTask")));

  }

  @Test
  @DisplayName("What a handler method changes on the aggregate is saved")
  public void whatTheMethodChangedIsSaved() {

    final var aggregate = aggregate("4", "recorded");

    noteService
        .recordNoteOf(aggregate, "TheServiceTask", SampleNoteDetails.Kind.CREATED)
        .orElseThrow();

    assertEquals("noteOfTheServiceTask", persistence.stored("4").getTouched());

  }

  @Test
  @DisplayName("A note somebody only reads leaves the store as it was")
  public void aReadingCallLeavesTheStoreAlone() {

    final var aggregate = aggregate("5", "read-only");

    noteService
        .noteOf(aggregate, "TheServiceTask", SampleNoteDetails.Kind.CREATED)
        .orElseThrow();

    assertEquals(null, persistence.stored("5").getTouched());

  }

  @Test
  @DisplayName("A note recorded inside a transaction of the caller is committed with it")
  public void aRecordedNoteRidesTheCallersTransaction() throws Exception {

    final var aggregate = aggregate("7", "in-the-callers-transaction");

    userTransaction.begin();
    try {
      noteService
          .recordNoteOf(aggregate, "TheServiceTask", SampleNoteDetails.Kind.CREATED)
          .orElseThrow();
    } finally {
      userTransaction.commit();
    }

    assertEquals("noteOfTheServiceTask", persistence.stored("7").getTouched());

  }

  @Test
  @DisplayName("Without a transaction of the caller, VanillaBP opens one instead of refusing")
  public void aCallWithoutACallersTransactionGetsOne() {

    final var aggregate = aggregate("8", "no-transaction-here");

    // the extension asks for nothing about transactions, and an extension called from a
    // worker thread of a BPMS is the normal case rather than a mistake
    noteService
        .recordNoteOf(aggregate, "TheServiceTask", SampleNoteDetails.Kind.CREATED)
        .orElseThrow();

    assertEquals("noteOfTheServiceTask", persistence.stored("8").getTouched());

  }

  @Test
  @DisplayName("The extension learns which BPMS holds the workflow")
  public void theElectionAnswersTheExtension() {

    final var aggregate = aggregate("6", "elected");

    assertEquals("demo1", noteService.bpmsHolding(aggregate));
    assertEquals("demo1", election.adapterIdOfWorkflow(MODULE, PROCESS, "6"));

  }

  @Test
  @DisplayName("A workflow no BPMS knows is refused with a message naming the adapters asked")
  public void anUnknownWorkflowIsRefusedGuiding() {

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> election.adapterIdOfWorkflow(MODULE, PROCESS, EveryWorkflowRunsHere.UNKNOWN_AGGREGATE_ID));
    assertTrue(failure.getMessage().contains("demo1"));

  }

  @Test
  @DisplayName("The extension is told which aggregate a process works on and which processes a module has")
  public void theExtensionReadsWhatTheRegistryKnows() {

    assertEquals(java.util.Optional.of(NoteAggregate.class), handlers.workflowAggregateOf(MODULE, PROCESS));
    assertEquals(java.util.Optional.empty(), handlers.workflowAggregateOf(MODULE, "NobodyDeclaredThis"));
    assertEquals(List.of(PROCESS), handlers.bpmnProcessesOf(MODULE));
    assertEquals(List.of(), handlers.bpmnProcessesOf("no-such-module"));

  }

  @Test
  @DisplayName("The extension is told the name a modeller wrote on a BPMN element")
  public void theExtensionReadsTheBpmnName() {

    assertEquals(
        java.util.Optional.of(NoteTaskWiringSource.NAME),
        handlers.bpmnTaskNameOf(MODULE, PROCESS, NoteTaskWiringSource.ACTIVITY_ID));
    // an element the adapter reported no name for, and a process nothing was deployed
    // under, are both answered with nothing rather than with a guess
    assertEquals(java.util.Optional.empty(), handlers.bpmnTaskNameOf(MODULE, PROCESS, "TheServiceTask"));
    assertEquals(java.util.Optional.empty(), handlers.bpmnTaskNameOf(MODULE, "NobodyDeployedThis", "TheUserTask"));

  }

  @Test
  @DisplayName("An extension is told the transaction the workflow's own writes run in")
  public void anExtensionResolvesTheTransactionOfTheAggregate() {

    // this application contributed no runner of its own, so the answer is the platform's
    // JTA - and it is the very object the process services write through, not a second
    // one built next to it
    assertEquals("the JTA transaction of Quarkus", transactions.describeResolutionFor(NoteAggregate.class));
    assertNotNull(transactions.runnerOf(NoteAggregate.class));

  }

  @Test
  @DisplayName("Joining a transaction which is not open is refused naming the reason")
  public void joiningNothingIsRefusedGuiding() {

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> transactions
            .runnerOf(NoteAggregate.class)
            .inCurrent(() -> "never reached"));

    assertTrue(failure.getMessage().contains("no transaction is active"), failure.getMessage());
    assertTrue(failure.getMessage().contains("runInCurrentTransaction"), failure.getMessage());

  }

  @Test
  @DisplayName("A workflow module overrides what the extension is configured with globally")
  public void theWorkflowModuleOverridesTheGlobalSetting() {

    assertEquals("Servus", noteService.configuredGreeting());
    assertEquals("Hello", properties.extensionProperty(null, "sample", "greeting"));
    // what the module says nothing about stays what the global section says
    assertEquals("whatever", properties.extensionProperty(MODULE, "sample", "unused"));

  }

}
