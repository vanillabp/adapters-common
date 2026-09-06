package io.vanillabp.integration.test.activation;

import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The workflow is where the correlation expects it to be, so the election has something
 * to elect. Nothing in this test is about the election itself.
 */
@ApplicationScoped
public class ActivationAwarenessSource implements DummyTaskAwarenessSource {

  @Override
  public WorkflowAwareness awarenessOfTask(
      final String adapterId,
      final Object workflowAggregateId,
      final String taskId) {

    return WorkflowAwareness.ACTIVE;

  }

  @Override
  public WorkflowAwareness awarenessOfWorkflow(
      final String adapterId,
      final Object workflowAggregateId) {

    return WorkflowAwareness.ACTIVE;

  }

}
