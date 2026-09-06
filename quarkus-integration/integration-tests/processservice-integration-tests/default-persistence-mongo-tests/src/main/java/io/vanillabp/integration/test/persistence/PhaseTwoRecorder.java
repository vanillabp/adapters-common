package io.vanillabp.integration.test.persistence;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.vanillabp.bpmsdouble.DummyPhaseTwoListener;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Records what the dummy adapter did in phase two, so a test can wait for the outbox
 * dispatcher's thread to have arrived.
 */
@ApplicationScoped
public class PhaseTwoRecorder implements DummyPhaseTwoListener {

  private final List<String> startedWorkflows = new CopyOnWriteArrayList<>();

  private final List<String> completedTasks = new CopyOnWriteArrayList<>();

  @Override
  public void startedWorkflowPhaseTwo(
      final Object workflowAggregateId) {

    startedWorkflows.add(String.valueOf(workflowAggregateId));

  }

  @Override
  public void completedTaskPhaseTwo(
      final Object workflowAggregateId,
      final String taskId) {

    completedTasks.add(workflowAggregateId
        + ":"
        + taskId);

  }

  public List<String> getStartedWorkflows() {

    return List.copyOf(startedWorkflows);

  }

  public List<String> getCompletedTasks() {

    return List.copyOf(completedTasks);

  }

  /**
   * Waits until the workflow of the given aggregate was started in phase two.
   *
   * @param workflowAggregateId The aggregate's ID
   * @param timeoutMillis How long to wait
   * @return Whether phase two arrived in time
   */
  public boolean awaitStartedWorkflow(
      final String workflowAggregateId,
      final long timeoutMillis) throws InterruptedException {

    return await(startedWorkflows, workflowAggregateId, timeoutMillis);

  }

  /**
   * Waits until the given task was completed in phase two.
   *
   * @param workflowAggregateId The aggregate's ID
   * @param taskId The task's ID
   * @param timeoutMillis How long to wait
   * @return Whether phase two arrived in time
   */
  public boolean awaitCompletedTask(
      final String workflowAggregateId,
      final String taskId,
      final long timeoutMillis) throws InterruptedException {

    return await(
        completedTasks,
        workflowAggregateId
            + ":"
            + taskId,
        timeoutMillis);

  }

  private static boolean await(
      final List<String> recorded,
      final String entry,
      final long timeoutMillis) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + timeoutMillis;
    while (!recorded.contains(entry) && (System.currentTimeMillis() < deadline)) {
      Thread.sleep(50);
    }
    return recorded.contains(entry);

  }

}
