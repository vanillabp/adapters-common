package io.vanillabp.integration.test;

import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Steers the dummy adapter's task awareness per test: the answer set here is
 * returned for every probe (default {@link WorkflowAwareness#UNKNOWN_TO_BPMS}).
 */
@ApplicationScoped
public class SteerableTaskAwarenessSource implements DummyTaskAwarenessSource {

  private volatile WorkflowAwareness answer = WorkflowAwareness.UNKNOWN_TO_BPMS;

  public void answerWith(
      final WorkflowAwareness awareness) {

    this.answer = awareness;

  }

  @Override
  public WorkflowAwareness awarenessOfTask(
      final String adapterId,
      final Object workflowAggregateId,
      final String taskId) {

    return answer;

  }

  /**
   * How many WORKFLOW probes still answer UNKNOWN_TO_BPMS before the configured
   * answer applies - an eventually consistent BPMS: the workflow
   * exists, its BPMS just cannot find it yet.
   */
  private final java.util.concurrent.atomic.AtomicInteger invisibleProbes = new java.util.concurrent.atomic.AtomicInteger();

  /**
   * The window this adapter reports, or <code>null</code> for an immediately
   * consistent BPMS (the default of this test double).
   */
  private volatile io.vanillabp.integration.adapter.spi.WorkflowVisibilityDelay visibilityDelay;

  /**
   * Lets the next <code>probes</code> workflow probes answer "not visible yet" and
   * makes this adapter report the given window while doing so.
   *
   * @param probes How many probes report the workflow as unknown
   * @param window The window the adapter asks the core to wait
   */
  public void becomeVisibleAfter(
      final int probes,
      final java.time.Duration window) {

    invisibleProbes.set(probes);
    this.visibilityDelay = new io.vanillabp.integration.adapter.spi.WorkflowVisibilityDelay(
        window, java.time.Duration.ofMillis(20));

  }

  public void alwaysVisible() {

    invisibleProbes.set(0);
    this.visibilityDelay = null;

  }

  /**
   * @return How many "not visible yet" probes the current test has left over
   */
  public int remainingInvisibleProbes() {

    return invisibleProbes.get();

  }

  @Override
  public io.vanillabp.integration.adapter.spi.WorkflowVisibilityDelay workflowVisibilityDelay(
      final String adapterId) {

    return visibilityDelay;

  }

  @Override
  public WorkflowAwareness awarenessOfWorkflow(
      final String adapterId,
      final Object workflowAggregateId) {

    if (invisibleProbes.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0) {
      return WorkflowAwareness.UNKNOWN_TO_BPMS;
    }
    return answer;

  }

}
