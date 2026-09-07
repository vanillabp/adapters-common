package io.vanillabp.integration.extension.spi.service;

import io.vanillabp.integration.extension.spi.election.WorkflowElection;
import io.vanillabp.integration.extension.spi.handler.ExtensionHandlers;

/**
 * What an {@link AggregateServiceFactory} is handed when VanillaBP asks it for the
 * service of one workflow-aggregate class: which aggregate and which workflow it is
 * about, and the three things a service typically needs - the aggregate's persistence,
 * the handler methods of the application and the election.
 */
public interface AggregateServiceContext {

  /**
   * @return The workflow-aggregate class the service is built for
   */
  Class<?> getWorkflowAggregateClass();

  /**
   * @return The workflow module the aggregate's primary BPMN process belongs to
   */
  String getWorkflowModuleId();

  /**
   * The BPMN process the aggregate's <code>&#64;WorkflowService</code> declares as its
   * primary one - the same process the injectable
   * {@code ProcessService} of that aggregate addresses.
   *
   * @return The BPMN process ID
   */
  String getBpmnProcessId();

  /**
   * @param workflowAggregate The aggregate
   * @return Its ID, in the aggregate's own ID type
   */
  Object getWorkflowAggregateId(
      Object workflowAggregate);

  /**
   * @param workflowAggregateId The ID of a workflow aggregate, in its own type or
   *          serialized
   * @return The aggregate, or <code>null</code> if the store holds none of that ID
   */
  Object loadWorkflowAggregate(
      Object workflowAggregateId);

  /**
   * Saves an aggregate through the persistence VanillaBP resolved for it.
   *
   * @param workflowAggregate The aggregate to save
   * @return The attached aggregate
   */
  Object saveWorkflowAggregate(
      Object workflowAggregate);

  /**
   * @return The handler methods of the application, for the contracts this extension
   *         registered
   */
  ExtensionHandlers getHandlers();

  /**
   * @return The election, to find the BPMS holding a workflow of this aggregate
   */
  WorkflowElection getElection();

}
