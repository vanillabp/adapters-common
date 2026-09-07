package io.vanillabp.integration.adapter.migration.processservice;

import io.vanillabp.integration.extension.spi.election.WorkflowElection;

/**
 * The election, offered to extensions. It answers from the same process services every
 * operation of VanillaBP uses, which is what makes an extension follow a workflow
 * through a migration instead of always addressing the first-priority BPMS.
 * <p>
 * The process services are looked up in the {@link PhaseTwoRouter}, where both platforms
 * register them while their beans are created - so the election works for every BPMN
 * process the application serves, without a registry of its own.
 */
public final class ExtensionWorkflowElection implements WorkflowElection {

  private final PhaseTwoRouter router;

  /**
   * @param router The router holding the process services of this application
   */
  public ExtensionWorkflowElection(
      final PhaseTwoRouter router) {

    this.router = router;

  }

  @Override
  public String adapterIdOfWorkflow(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId) {

    final var processService = router.processServiceOf(workflowModuleId, bpmnProcessId);
    if (processService == null) {
      throw new IllegalStateException(
          """
              No @WorkflowService of this application declares BPMN process '%s' of workflow module \
              '%s', so which BPMS holds a workflow of it cannot be elected! The workflows this \
              application serves are: %s."""
              .formatted(
                  bpmnProcessId,
                  workflowModuleId,
                  String.join(", ", router.registeredWorkflows())));
    }
    return processService.adapterIdOfWorkflow(workflowAggregateId);

  }

}
