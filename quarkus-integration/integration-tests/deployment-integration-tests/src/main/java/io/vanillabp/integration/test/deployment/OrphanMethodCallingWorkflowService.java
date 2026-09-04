package io.vanillabp.integration.test.deployment;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Serves the process 'Calling' and declares a second method for a task nobody
 * modelled. Used by {@code OrphanMethodNextToUnclaimedProcessTest}: the process the
 * boot only warns about must not make the reverse direction of the wiring validation
 * any quieter.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = TaskAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "Calling"))
public class OrphanMethodCallingWorkflowService {

  @WorkflowTask
  public void juhu(
      final TaskAggregate aggregate) {

    aggregate.setStatus("processed");

  }

  @WorkflowTask(taskDefinition = "activityNobodyModelled")
  public void typo() {

  }

}
