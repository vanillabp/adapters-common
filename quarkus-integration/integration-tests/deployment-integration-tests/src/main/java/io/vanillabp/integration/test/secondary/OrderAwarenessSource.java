package io.vanillabp.integration.test.secondary;

import java.util.concurrent.atomic.AtomicInteger;

import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * What the BPMS is asked about a workflow, and how often. The count is the whole point of
 * the delivery record: an operation answered from it asks nobody.
 */
@ApplicationScoped
public class OrderAwarenessSource implements DummyTaskAwarenessSource {

  private final AtomicInteger probes = new AtomicInteger();

  private volatile WorkflowAwareness answer = WorkflowAwareness.UNKNOWN_TO_BPMS;

  /**
   * @param awareness What every probe reports from now on
   */
  public void answerWith(
      final WorkflowAwareness awareness) {

    this.answer = awareness;

  }

  public void forgetTheProbesSoFar() {

    probes.set(0);

  }

  /**
   * @return How often a BPMS was asked since the last reset
   */
  public int probes() {

    return probes.get();

  }

  @Override
  public WorkflowAwareness awarenessOfTask(
      final String adapterId,
      final Object workflowAggregateId,
      final String taskId) {

    probes.incrementAndGet();
    return answer;

  }

}
