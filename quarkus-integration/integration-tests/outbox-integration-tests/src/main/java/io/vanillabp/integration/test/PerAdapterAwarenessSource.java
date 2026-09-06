package io.vanillabp.integration.test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Steers the dummy adapters' awareness PER ADAPTER ID (multi-adapter election
 * tests) and records every probe so tests can assert which adapter was
 * probed how often (e.g. that a cached election skips the walk).
 */
@ApplicationScoped
public class PerAdapterAwarenessSource implements DummyTaskAwarenessSource {

  private final Map<String, WorkflowAwareness> answersByAdapterId = new ConcurrentHashMap<>();

  private final List<String> probes = new CopyOnWriteArrayList<>();

  public void answerFor(
      final String adapterId,
      final WorkflowAwareness awareness) {

    answersByAdapterId.put(adapterId, awareness);

  }

  /**
   * @return All recorded probes as <code>&lt;adapterId&gt;:&lt;taskId&gt;</code>
   *         (task ID <code>null</code> for workflow probes)
   */
  public List<String> getProbes() {

    return List.copyOf(probes);

  }

  public long countProbesOf(
      final String adapterId) {

    return probes
        .stream()
        .filter(probe -> probe.startsWith(adapterId
            + ":"))
        .count();

  }

  public void reset() {

    probes.clear();
    answersByAdapterId.clear();

  }

  @Override
  public WorkflowAwareness awarenessOfTask(
      final String adapterId,
      final Object workflowAggregateId,
      final String taskId) {

    probes.add(adapterId
        + ":"
        + taskId);
    return answersByAdapterId.getOrDefault(adapterId, WorkflowAwareness.UNKNOWN_TO_BPMS);

  }

}
