package io.vanillabp.integration.test.inheritance;

import io.vanillabp.extension.sample.SampleNote;
import io.vanillabp.extension.sample.SampleNoteDetails;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Three handler methods a developer can read in their own source and VanillaBP cannot:
 * one which is not public, one which the subclass below overrides without repeating the
 * annotation, and one of an EXTENSION which is not public either - the scan of an
 * extension's contract reads the public methods of the class the same way, so the method
 * is lost for the same reason and is named the same way.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = InvisibleHandlersAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "InvisibleHandlers"))
public class InvisibleHandlersWorkflowService extends OverriddenHandlerBase {

  @WorkflowTask
  protected void tooWellHidden(
      final InvisibleHandlersAggregate aggregate) {

    aggregate.setServedBy("tooWellHidden");

  }

  @SampleNote(element = "TheServiceTask")
  protected SampleNoteDetails noteNobodyReaches(
      final SampleNoteDetails prefilled) {

    return prefilled;

  }

  @Override
  public void overriddenTask(
      final InvisibleHandlersAggregate aggregate) {

    aggregate.setServedBy("overriddenTask");

  }

}
