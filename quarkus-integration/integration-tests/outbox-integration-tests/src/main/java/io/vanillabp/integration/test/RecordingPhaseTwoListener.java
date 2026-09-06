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

  /**
   * Recorded phase-two starts as "adapterId:aggregateId" - the migration-scenario
   * test asserts WHICH adapter instance started the workflow.
   */
  private final List<String> startedByAdapter = new CopyOnWriteArrayList<>();

  private final AtomicInteger failuresRemaining = new AtomicInteger(0);

  /**
   * Whether the failures this listener raises are the kind the dummy adapter reports
   * as permanent.
   */
  private volatile boolean failPermanently = false;

  @Override
  public void startedWorkflowPhaseTwo(
      final Object workflowAggregateId) {

    invocations.add(workflowAggregateId);
    if (failuresRemaining.getAndUpdate(remaining -> remaining > 0 ? remaining - 1 : 0) > 0) {
      if (failPermanently) {
        throw new io.vanillabp.bpmsdouble.DummyPermanentFailure(
            "phase two failed permanently for testing purposes");
      }
      throw new RuntimeException("phase two failed for testing purposes");
    }

  }

  @Override
  public void startedWorkflowPhaseTwo(
      final String adapterId,
      final Object workflowAggregateId) {

    startedByAdapter.add(adapterId
        + ":"
        + workflowAggregateId);
    startedWorkflowPhaseTwo(workflowAggregateId);

  }

  /**
   * The signals broadcast so far, as "signalName/phase" - a broadcast of a remote
   * BPMS may only happen in phase two, after the commit.
   */
  private final List<String> broadcastSignals = new CopyOnWriteArrayList<>();

  @Override
  public void broadcastSignal(
      final String signalName,
      final boolean phaseTwo) {

    broadcastSignals.add("%s/%s".formatted(signalName, phaseTwo
        ? "phase-two"
        : "phase-one"));

  }

  public List<String> getBroadcastSignals() {

    return List.copyOf(broadcastSignals);

  }

  public List<String> getStartedByAdapter() {

    return List.copyOf(startedByAdapter);

  }

  /**
   * Recorded phase-two task completions as "adapterId:aggregateId:taskId".
   */
  private final List<String> completedTasksByAdapter = new CopyOnWriteArrayList<>();

  @Override
  public void completedTaskPhaseTwo(
      final String adapterId,
      final Object workflowAggregateId,
      final String taskId) {

    completedTasksByAdapter.add(adapterId
        + ":"
        + workflowAggregateId
        + ":"
        + taskId);
    completedTaskPhaseTwo(workflowAggregateId, taskId);

  }

  public List<String> getCompletedTasksByAdapter() {

    return List.copyOf(completedTasksByAdapter);

  }

  /**
   * Recorded phase-two task completions as "aggregateId:taskId" and cancellations
   * as "aggregateId:taskId:errorCode".
   */
  private final List<String> completedTasks = new CopyOnWriteArrayList<>();

  private final List<String> canceledTasks = new CopyOnWriteArrayList<>();

  @Override
  public void completedTaskPhaseTwo(
      final Object workflowAggregateId,
      final String taskId) {

    completedTasks.add(workflowAggregateId
        + ":"
        + taskId);

  }

  @Override
  public void canceledTaskPhaseTwo(
      final Object workflowAggregateId,
      final String taskId,
      final String bpmnErrorCode) {

    canceledTasks.add(workflowAggregateId
        + ":"
        + taskId
        + ":"
        + bpmnErrorCode);

  }

  private final List<String> completedUserTasks = new CopyOnWriteArrayList<>();

  private final List<String> canceledUserTasks = new CopyOnWriteArrayList<>();

  @Override
  public void completedUserTaskPhaseTwo(
      final Object workflowAggregateId,
      final String taskId) {

    completedUserTasks.add(workflowAggregateId
        + ":"
        + taskId);

  }

  @Override
  public void canceledUserTaskPhaseTwo(
      final Object workflowAggregateId,
      final String taskId,
      final String bpmnErrorCode) {

    canceledUserTasks.add(workflowAggregateId
        + ":"
        + taskId
        + ":"
        + bpmnErrorCode);

  }

  /**
   * Recorded phase-two correlations as "aggregateId:messageName:correlationId"
   * (correlation id may be the literal "null") and starts-by-message as
   * "aggregateId:messageName".
   */
  private final List<String> correlatedMessages = new CopyOnWriteArrayList<>();

  private final List<String> startedByMessage = new CopyOnWriteArrayList<>();

  @Override
  public void correlatedMessagePhaseTwo(
      final Object workflowAggregateId,
      final String messageName,
      final String correlationId) {

    correlatedMessages.add(workflowAggregateId
        + ":"
        + messageName
        + ":"
        + correlationId);

  }

  @Override
  public void startedWorkflowByMessagePhaseTwo(
      final Object workflowAggregateId,
      final String messageName) {

    startedByMessage.add(workflowAggregateId
        + ":"
        + messageName);

  }

  public List<String> getCorrelatedMessages() {

    return List.copyOf(correlatedMessages);

  }

  public List<String> getStartedByMessage() {

    return List.copyOf(startedByMessage);

  }

  public List<String> getCompletedUserTasks() {

    return List.copyOf(completedUserTasks);

  }

  public List<String> getCanceledUserTasks() {

    return List.copyOf(canceledUserTasks);

  }

  public List<String> getCompletedTasks() {

    return List.copyOf(completedTasks);

  }

  public List<String> getCanceledTasks() {

    return List.copyOf(canceledTasks);

  }

  /**
   * Recorded pushes of a changed aggregate as "aggregateId:taskId:phase" (the task
   * id may be the literal "null" - that is the workflow's global scope).
   */
  private final List<String> aggregateChanges = new CopyOnWriteArrayList<>();

  @Override
  public void aggregateChanged(
      final Object workflowAggregateId,
      final String taskId,
      final boolean phaseTwo) {

    aggregateChanges.add("%s:%s:%s".formatted(workflowAggregateId, taskId, phaseTwo
        ? "phase-two"
        : "phase-one"));

  }

  public List<String> getAggregateChanges() {

    return List.copyOf(aggregateChanges);

  }

  /**
   * Lets the next dispatches fail with a failure the adapter reports as PERMANENT -
   * the store has to block such an entry right away.
   *
   * @param numberOfFailures How many dispatches fail
   */
  public void failNextDispatchesPermanently(
      final int numberOfFailures) {

    failPermanently = true;
    failNextDispatches(numberOfFailures);

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
    startedByAdapter.clear();
    completedTasksByAdapter.clear();
    completedTasks.clear();
    canceledTasks.clear();
    completedUserTasks.clear();
    canceledUserTasks.clear();
    correlatedMessages.clear();
    startedByMessage.clear();
    aggregateChanges.clear();
    failuresRemaining.set(0);
    failPermanently = false;

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
