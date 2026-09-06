package io.vanillabp.integration.test.persistence;

import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Lets the dummy adapter know every task probed, so operations on a running workflow
 * reach phase two instead of being rejected as unknown.
 */
@ApplicationScoped
public class ActiveTaskAwarenessSource implements DummyTaskAwarenessSource {

  @Override
  public WorkflowAwareness awarenessOfTask(
      final String adapterId,
      final Object workflowAggregateId,
      final String taskId) {

    return WorkflowAwareness.ACTIVE;

  }

}
