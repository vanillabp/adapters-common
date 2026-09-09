package io.vanillabp.integration.test.secondary;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * One workflow service, two BPMN processes: the one the application addresses and the one
 * it calls. The task of the called process is asynchronous, so the delivery leaves it open
 * and its record is what a later completion is elected from.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = OrderAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "Ordering"),
    secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = "Shipping"))
public class OrderWorkflowService {

  @WorkflowTask(taskDefinition = "orderTask")
  public void orderTask(
      final OrderAggregate aggregate) {

    aggregate.setStatus("ordered");

  }

  @WorkflowTask(taskDefinition = "awaitShipment")
  public void awaitShipment(
      final OrderAggregate aggregate,
      @TaskId final String taskId) {

    aggregate.setStatus("awaiting-shipment");

  }

}
