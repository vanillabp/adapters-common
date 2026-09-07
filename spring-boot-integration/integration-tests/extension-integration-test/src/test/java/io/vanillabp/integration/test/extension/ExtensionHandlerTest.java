package io.vanillabp.integration.test.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.extension.sample.SampleNote;
import io.vanillabp.extension.sample.SampleNoteDetails;
import io.vanillabp.integration.extension.spi.handler.ExtensionHandlers;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What an extension gets from VanillaBP for an annotation of its own: the methods of the
 * application's workflow services are found, matched, invoked with the parameters the
 * contract allows, and what they return is handed back - the mechanics of
 * <code>&#64;WorkflowTask</code>, without a line of extension-specific code in the core.
 */
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
public class ExtensionHandlerTest {

  @Autowired
  private NotedWorkflowService workflowService;

  @Autowired
  private UnnotedWorkflowService unnotedWorkflowService;

  @Autowired
  private ExtensionHandlers handlers;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private NotedAggregateRepository aggregates;

  private NotedAggregate startWorkflow(
      final String content) {

    return transactionTemplate.execute(status -> {
      final var aggregate = new NotedAggregate();
      aggregate.setContent(content);
      return workflowService
          .getProcessService()
          .startWorkflow(aggregate);
    });

  }

  @Test
  @DisplayName("A method of the extension's annotation is invoked with everything its contract allows")
  public void everyParameterKindIsBound() {

    final var aggregate = startWorkflow("a-loan");

    final var note = workflowService
        .getNoteService()
        .noteOf(aggregate, "Activity_1c9pa8d", SampleNoteDetails.Kind.CREATED)
        .orElseThrow();

    // the workflow aggregate VanillaBP loaded, the note the extension prefilled, the
    // value of the extension's own annotated parameter, and a process variable
    // "Servus" rather than "Hello": the workflow module overrides what the extension
    // is configured with globally
    assertEquals("a-loan/Servus Activity_1c9pa8d/CREATED/CREATED", note.getTitle());

  }

  @Test
  @DisplayName("A method naming no element serves the key its own name is")
  public void aMethodIsMatchedByItsName() {

    final var aggregate = startWorkflow("named-by-method");

    final var note = workflowService
        .getNoteService()
        .noteOf(aggregate, "endOfTheProcess", SampleNoteDetails.Kind.COMPLETED)
        .orElseThrow();

    assertEquals("the end", note.getTitle());

  }

  @Test
  @DisplayName("An element no method serves is answered with nothing, and that is not an error")
  public void anUnservedElementIsNoError() {

    final var aggregate = startWorkflow("unserved");

    assertTrue(
        workflowService
            .getNoteService()
            .noteOf(aggregate, "StartEvent_1", SampleNoteDetails.Kind.CREATED)
            .isEmpty());
    assertFalse(
        handlers
            .hasHandler(SampleNote.class, "extension-module", "DummyProcess", java.util.List.of("StartEvent_1")));
    assertTrue(
        handlers
            .hasHandler(
                SampleNote.class,
                "extension-module",
                "DummyProcess",
                java.util.List.of("Activity_1c9pa8d")));

  }

  @Test
  @DisplayName("What a handler method changes on the aggregate is saved")
  public void whatTheMethodChangedIsSaved() {

    final var aggregate = startWorkflow("recorded");

    workflowService
        .getNoteService()
        .recordNoteOf(aggregate, "Activity_1c9pa8d", SampleNoteDetails.Kind.CREATED)
        .orElseThrow();

    assertEquals(
        "noteOfTheServiceTask",
        aggregates
            .findById(aggregate.getId())
            .orElseThrow()
            .getTouched());

  }

  @Test
  @DisplayName("A call which says so runs in the transaction of its caller")
  public void aCallMayRunInTheCallersTransaction() {

    final var aggregate = startWorkflow("in-the-callers-transaction");

    // rolled back by the caller: an invocation which opened a transaction of its own
    // would have saved what the method changed either way
    transactionTemplate
        .execute(status -> {
          workflowService
              .getNoteService()
              .recordNoteInTheCallersTransaction(aggregate, "Activity_1c9pa8d", SampleNoteDetails.Kind.CREATED)
              .orElseThrow();
          status.setRollbackOnly();
          return null;
        });

    assertNull(
        aggregates
            .findById(aggregate.getId())
            .orElseThrow()
            .getTouched());

  }

  @Test
  @DisplayName("A workflow service without a method of the extension is simply not registered")
  public void aWorkflowServiceWithoutSuchMethodsIsNotRegistered() {

    assertFalse(
        handlers
            .hasHandler(
                SampleNote.class,
                "extension-module",
                "UnnotedProcess",
                java.util.List.of("Activity_1c9pa8d", "endOfTheProcess")));
    // and its own process service still works, which is what "optional" means here
    final var aggregate = transactionTemplate.execute(status -> {
      final var unnoted = new UnnotedAggregate();
      unnoted.setContent("no extension here");
      return unnotedWorkflowService
          .getProcessService()
          .startWorkflow(unnoted);
    });
    assertEquals("no extension here", aggregate.getContent());

  }

}
