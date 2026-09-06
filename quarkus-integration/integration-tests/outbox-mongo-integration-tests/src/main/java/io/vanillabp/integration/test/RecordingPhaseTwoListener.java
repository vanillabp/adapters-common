package io.vanillabp.integration.test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import io.vanillabp.bpmsdouble.DummyPhaseTwoListener;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Records phase-two invocations of the dummy adapter and optionally fails a
 * configurable number of dispatches (to test retry behavior of the outbox).
 */
@ApplicationScoped
public class RecordingPhaseTwoListener implements DummyPhaseTwoListener {

  private final List<Object> invocations = new CopyOnWriteArrayList<>();

  private final AtomicInteger failuresRemaining = new AtomicInteger(0);

  @Override
  public void startedWorkflowPhaseTwo(
      final Object workflowAggregateId) {

    invocations.add(workflowAggregateId);
    if (failuresRemaining.getAndUpdate(remaining -> remaining > 0 ? remaining - 1 : 0) > 0) {
      throw new RuntimeException("phase two failed for testing purposes");
    }

  }

  public void failNextDispatches(
      final int numberOfFailures) {

    failuresRemaining.set(numberOfFailures);

  }

  public List<Object> getInvocations() {

    return List.copyOf(invocations);

  }

  public void reset() {

    invocations.clear();
    failuresRemaining.set(0);

  }

  /**
   * Waits until at least the given number of phase-two invocations were recorded.
   * <p>
   * This says that the ADAPTER was called, not that the operation is over: the listener
   * runs inside the dispatch, and the dispatcher marks the outbox entry DONE - which is
   * what frees its idempotency key - only after the dispatch returned. On return the
   * entry may still be waiting to be marked, so a repetition of the same operation
   * planned right here can be discarded as a duplicate. A test which needs the key to
   * be free has to wait for the ENTRY, in the store.
   *
   * @param numberOfInvocations The number of invocations to wait for
   * @param timeoutMillis How long to wait at most
   * @return All invocations recorded so far
   * @throws AssertionError If the invocations did not happen within the timeout
   */
  public List<Object> awaitInvocations(
      final int numberOfInvocations,
      final long timeoutMillis) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + timeoutMillis;
    while (invocations.size() < numberOfInvocations) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError(
            "Expected at least %d phase-two invocation(s) within %dms but got %d!"
                .formatted(numberOfInvocations, timeoutMillis, invocations.size()));
      }
      Thread.sleep(50);
    }
    return getInvocations();

  }

}
