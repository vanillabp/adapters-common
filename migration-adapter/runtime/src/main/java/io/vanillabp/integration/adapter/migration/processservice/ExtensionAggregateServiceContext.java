package io.vanillabp.integration.adapter.migration.processservice;

import io.vanillabp.integration.extension.spi.election.WorkflowElection;
import io.vanillabp.integration.extension.spi.handler.ExtensionHandlers;
import io.vanillabp.integration.extension.spi.service.AggregateServiceContext;

/**
 * What an extension's {@code AggregateServiceFactory} is handed for one
 * workflow-aggregate class: the process service of that aggregate's primary BPMN process
 * answers everything about the aggregate, and the handler methods and the election come
 * from the platform's own beans.
 *
 * @param <A> The workflow-aggregate type
 */
public final class ExtensionAggregateServiceContext<A> implements AggregateServiceContext {

  private final MigrationProcessService<A> processService;

  private final ExtensionHandlers handlers;

  private final WorkflowElection election;

  /**
   * @param processService The process service of the aggregate's primary BPMN process
   * @param handlers The handler methods of the extensions
   * @param election The election
   */
  public ExtensionAggregateServiceContext(
      final MigrationProcessService<A> processService,
      final ExtensionHandlers handlers,
      final WorkflowElection election) {

    this.processService = processService;
    this.handlers = handlers;
    this.election = election;

  }

  @Override
  public Class<?> getWorkflowAggregateClass() {

    return processService.getWorkflowAggregateClass();

  }

  @Override
  public String getWorkflowModuleId() {

    return processService.getWorkflowModuleId();

  }

  @Override
  public String getBpmnProcessId() {

    return processService.getBpmnProcessId();

  }

  @Override
  public Object getWorkflowAggregateId(
      final Object workflowAggregate) {

    return processService.getWorkflowAggregateId(cast(workflowAggregate));

  }

  @Override
  public Object loadWorkflowAggregate(
      final Object workflowAggregateId) {

    return processService
        .loadWorkflowAggregateById(processService.convertAggregateId(String.valueOf(workflowAggregateId)));

  }

  @Override
  public Object saveWorkflowAggregate(
      final Object workflowAggregate) {

    return processService.saveWorkflowAggregate(cast(workflowAggregate));

  }

  @Override
  public ExtensionHandlers getHandlers() {

    return handlers;

  }

  @Override
  public WorkflowElection getElection() {

    return election;

  }

  @SuppressWarnings("unchecked")
  private A cast(
      final Object workflowAggregate) {

    return (A) processService
        .getWorkflowAggregateClass()
        .cast(workflowAggregate);

  }

}
