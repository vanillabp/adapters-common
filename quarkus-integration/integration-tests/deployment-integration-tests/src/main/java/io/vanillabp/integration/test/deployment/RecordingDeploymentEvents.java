package io.vanillabp.integration.test.deployment;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.vanillabp.bpmsdouble.DummyDeploymentListener;
import io.vanillabp.bpmsdouble.DummyPhaseTwoListener;
import io.vanillabp.extension.dummy.runtime.DummyExtensionListener;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Records the deployment-pipeline calls of the dummy adapter, the dummy extension
 * and the dummy adapter's phase-two invocations in ONE ordered event list, so tests
 * can assert the global ordering across all participants. Event formats:
 * <ul>
 *   <li><code>adapter:&lt;adapterId&gt;:&lt;method&gt;:&lt;moduleId&gt;[:&lt;detail&gt;]</code></li>
 *   <li><code>extension:&lt;method&gt;:&lt;moduleId&gt;[:&lt;detail&gt;]</code></li>
 *   <li><code>phaseTwo:&lt;aggregateId&gt;</code></li>
 * </ul>
 */
@ApplicationScoped
public class RecordingDeploymentEvents implements DummyDeploymentListener, DummyExtensionListener, DummyPhaseTwoListener {

  private final List<String> events = new CopyOnWriteArrayList<>();

  @Override
  public void onPipelineCall(
      final String adapterId,
      final String method,
      final String workflowModuleId,
      final String detail) {

    events.add(withDetail("adapter:%s:%s:%s".formatted(adapterId, method, workflowModuleId), detail));

  }

  @Override
  public void onPipelineCall(
      final String method,
      final String workflowModuleId,
      final String detail) {

    events.add(withDetail("extension:%s:%s".formatted(method, workflowModuleId), detail));

  }

  @Override
  public void startedWorkflowPhaseTwo(
      final Object workflowAggregateId) {

    events.add("phaseTwo:%s".formatted(workflowAggregateId));

  }

  private static String withDetail(
      final String event,
      final String detail) {

    return detail == null
        ? event
        : event
            + ":"
            + detail;

  }

  public void record(
      final String event) {

    events.add(event);

  }

  public List<String> getEvents() {

    return List.copyOf(events);

  }

  /**
   * @param event The exact event to look up
   * @return The index of the event's first occurrence or -1
   */
  public int indexOf(
      final String event) {

    return getEvents().indexOf(event);

  }

  /**
   * Waits until an event starting with the given prefix was recorded.
   *
   * @param eventPrefix The prefix to wait for
   * @param timeoutMillis How long to wait at most
   * @return All events recorded so far
   */
  public List<String> awaitEvent(
      final String eventPrefix,
      final long timeoutMillis) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + timeoutMillis;
    while (getEvents().stream().noneMatch(event -> event.startsWith(eventPrefix))) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError(
            "Expected an event starting with '%s' within %dms but got: %s"
                .formatted(eventPrefix, timeoutMillis, getEvents()));
      }
      Thread.sleep(50);
    }
    return getEvents();

  }

}
