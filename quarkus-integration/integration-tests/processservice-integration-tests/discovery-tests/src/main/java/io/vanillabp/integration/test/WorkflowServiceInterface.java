package io.vanillabp.integration.test;

/**
 * The defect: <code>&#64;WorkflowService</code> on an interface, with the handler declared
 * in the interface as well. Jandex reports the interface as the annotated type, so the
 * interface used to become the workflow service and the methods declared here used to
 * serve the tasks, invoked on an instance of
 * {@link WorkflowServiceImplementingAnInterface}. See {@code WorkflowServiceOnAnInterfaceTest}.
 */
@io.vanillabp.spi.service.WorkflowService(workflowAggregateClass = Aggregate.class)
public interface WorkflowServiceInterface {

  @io.vanillabp.spi.service.WorkflowTask(taskDefinition = "processTask")
  void processTask(
      Aggregate aggregate);

}
