package io.vanillabp.integration.test.extension;

import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Lets the BPMS double report the workflows of this scenario as running, so the election
 * an extension asks has something to elect. One id is deliberately unknown to everybody.
 */
@ApplicationScoped
public class EveryWorkflowRunsHere implements DummyTaskAwarenessSource {

  /**
   * The id no workflow of this scenario ever has.
   */
  public static final String UNKNOWN_AGGREGATE_ID = "4711";

  @Override
  public WorkflowAwareness awarenessOfTask(
      final String adapterId,
      final Object workflowAggregateId,
      final String taskId) {

    return UNKNOWN_AGGREGATE_ID.equals(String.valueOf(workflowAggregateId))
        ? WorkflowAwareness.UNKNOWN_TO_BPMS
        : WorkflowAwareness.ACTIVE;

  }

}
