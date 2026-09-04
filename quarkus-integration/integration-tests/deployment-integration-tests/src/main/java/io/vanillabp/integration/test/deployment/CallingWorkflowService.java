package io.vanillabp.integration.test.deployment;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Serves the process 'Calling' of a BPMN file which carries a second executable
 * process, 'Called', that nobody serves. Used by {@code UnclaimedBpmnProcessTest}: the
 * unclaimed process next to this one is reported by a WARN while this one is wired as
 * before.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = TaskAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "Calling"))
public class CallingWorkflowService {

  @WorkflowTask
  public void juhu(
      final TaskAggregate aggregate) {

    aggregate.setStatus("processed");

  }

}
