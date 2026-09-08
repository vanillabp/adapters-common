package io.vanillabp.integration.test.extension;

import org.springframework.stereotype.Service;

import io.vanillabp.extension.sample.SampleNote;
import io.vanillabp.extension.sample.SampleNoteDetails;
import io.vanillabp.extension.sample.SampleNoteEvent;
import io.vanillabp.extension.sample.SampleNoteService;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;

/**
 * The application's side of the sample extension: methods carrying the extension's own
 * annotation, right next to the {@code ProcessService} of the same aggregate - which is
 * what an application of the Business Cockpit looks like.
 */
@Service
@WorkflowService(
    workflowAggregateClass = NotedAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "DummyProcess"))
public class NotedWorkflowService {

  private final ProcessService<NotedAggregate> processService;

  /**
   * The extension's per-aggregate service, injected like the process service and
   * optional - the second aggregate of this scenario never asks for it.
   */
  private final SampleNoteService<NotedAggregate> noteService;

  public NotedWorkflowService(
      final ProcessService<NotedAggregate> processService,
      final SampleNoteService<NotedAggregate> noteService) {

    this.processService = processService;
    this.noteService = noteService;

  }

  public ProcessService<NotedAggregate> getProcessService() {

    return processService;

  }

  public SampleNoteService<NotedAggregate> getNoteService() {

    return noteService;

  }

  /**
   * Matched by the element the annotation names, and taking every kind of parameter the
   * contract allows: the workflow aggregate, the prefilled note, what happened to the
   * element and a process variable.
   *
   * @param aggregate The workflow aggregate VanillaBP loaded
   * @param prefilled The note this extension prefilled
   * @param kind What happened to the element
   * @param kindVariable The same, as a process variable the invocation carried
   * @return The note the extension publishes
   */
  @SampleNote(element = "Activity_1c9pa8d")
  public SampleNoteDetails noteOfTheServiceTask(
      final NotedAggregate aggregate,
      final SampleNoteDetails prefilled,
      @SampleNoteEvent final SampleNoteDetails.Kind kind,
      @TaskParam("kind") final String kindVariable) {

    aggregate.setTouched("noteOfTheServiceTask");
    prefilled
        .setTitle(
            "%s/%s/%s/%s".formatted(aggregate.getContent(), prefilled.getTitle(), kind, kindVariable));
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

  /**
   * A method of the extension which VanillaBP never sees, because the scan of a handler
   * contract reads the PUBLIC methods of a workflow service class the way the scan of
   * <code>&#64;WorkflowTask</code> does. Nothing about the wiring gives that away for an
   * extension - it simply behaves as if this method had never been written - so the boot
   * names it, which is what {@code ExtensionHandlerMethodsNobodySeesTest} reads.
   *
   * @param prefilled The note this extension prefilled
   * @return What would have been published if anybody could call it
   */
  @SampleNote(element = "Activity_TooWellHidden")
  protected SampleNoteDetails noteNobodyReaches(
      final SampleNoteDetails prefilled) {

    prefilled.setTitle("nobody reaches this");
    return prefilled;

  }

}
