package io.vanillabp.bpmsdouble;

import io.vanillabp.integration.adapter.spi.WorkflowAwareness;

/**
 * Test hook steering the dummy adapter's
 * {@link DummyProcessService#awarenessOfTask} answer -
 * integration tests probe the core's adapter election (the
 * {@code WorkflowLocator} walk) without a real BPMS. Without such a bean the dummy
 * does not know any task.
 */
public interface DummyTaskAwarenessSource {

  /**
   * The awareness the dummy adapter of the given adapter ID reports for the task.
   *
   * @param adapterId The dummy adapter's ID (several instances may be configured)
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param taskId The task's ID
   * @return The awareness or <code>null</code> to let another source answer
   *         (defaulting to {@link WorkflowAwareness#UNKNOWN_TO_BPMS})
   */
  WorkflowAwareness awarenessOfTask(
      String adapterId,
      Object workflowAggregateId,
      String taskId);

  /**
   * The awareness the dummy adapter reports for a WORKFLOW - defaults to the
   * service-task answer (probes for message correlation).
   *
   * @param adapterId The dummy adapter's ID
   * @param workflowAggregateId The ID of the workflow aggregate
   * @return The awareness or <code>null</code> to let another source answer
   */
  default WorkflowAwareness awarenessOfWorkflow(
      final String adapterId,
      final Object workflowAggregateId) {

    return awarenessOfTask(adapterId, workflowAggregateId, null);

  }

  /**
   * The awareness the dummy adapter reports for a USER task - defaults to the
   * service-task answer.
   *
   * @param adapterId The dummy adapter's ID
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param taskId The user task's ID
   * @return The awareness or <code>null</code> to let another source answer
   */

  /**
   * Whether the dummy adapter of the given ID can ask its BPMS whether it holds a
   * workflow - the capability the core refuses to combine with a second adapter
   * (a Camunda 8 cluster without secondary storage answers <code>false</code> here).
   *
   * @param adapterId The dummy adapter's ID
   * @return Whether the workflow probe of that adapter asks rather than guesses
   */
  default boolean canLocateWorkflows(
      final String adapterId) {

    return true;

  }

  default WorkflowAwareness awarenessOfUserTask(
      final String adapterId,
      final Object workflowAggregateId,
      final String taskId) {

    return awarenessOfTask(adapterId, workflowAggregateId, taskId);

  }


  /**
   * The visibility window the dummy adapter reports: how long the core
   * keeps asking a hinted adapter which answers
   * {@link WorkflowAwareness#UNKNOWN_TO_BPMS}. <code>null</code> means none, which
   * is what an adapter of an immediately consistent BPMS reports.
   *
   * @param adapterId The dummy adapter's ID
   * @return The delay or <code>null</code>
   */
  default io.vanillabp.integration.adapter.spi.WorkflowVisibilityDelay workflowVisibilityDelay(
      final String adapterId) {

    return null;

  }

}
