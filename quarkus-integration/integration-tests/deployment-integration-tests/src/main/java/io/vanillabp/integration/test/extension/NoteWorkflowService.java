package io.vanillabp.integration.test.extension;

import io.vanillabp.extension.sample.SampleNote;
import io.vanillabp.extension.sample.SampleNoteDetails;
import io.vanillabp.extension.sample.SampleNoteEvent;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The application's side of the sample extension on Quarkus - the same two methods the
 * Spring Boot integration's scenario has.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = NoteAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "NoteProcess"))
public class NoteWorkflowService {

  /**
   * Matched by the element the annotation names, and taking every kind of parameter the
   * contract allows.
   *
   * @param aggregate The workflow aggregate VanillaBP loaded
   * @param prefilled The note this extension prefilled
   * @param kind What happened to the element
   * @param kindVariable The same, as a process variable the invocation carried
   * @return The note the extension publishes
   */
  @SampleNote(element = "TheServiceTask")
  public SampleNoteDetails noteOfTheServiceTask(
      final NoteAggregate aggregate,
      final SampleNoteDetails prefilled,
      @SampleNoteEvent final SampleNoteDetails.Kind kind,
      @TaskParam("kind") final String kindVariable) {

    aggregate.setTouched("noteOfTheServiceTask");
    prefilled
        .setTitle("%s/%s/%s/%s".formatted(aggregate.getContent(), prefilled.getTitle(), kind, kindVariable));
    return prefilled;

  }

  /**
   * Matched by its own name, which is the convention VanillaBP's own annotations follow.
   *
   * @param prefilled The note this extension prefilled
   * @return The note the extension publishes
   */
  @SampleNote
  public SampleNoteDetails endOfTheProcess(
      final SampleNoteDetails prefilled) {

    prefilled.setTitle("the end");
    return prefilled;

  }

}
