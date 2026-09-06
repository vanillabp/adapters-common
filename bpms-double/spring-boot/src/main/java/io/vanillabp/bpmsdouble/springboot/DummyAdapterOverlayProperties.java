package io.vanillabp.bpmsdouble.springboot;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * The dummy adapter's OVERLAY of the shared <code>vanillabp.*</code> configuration
 * tree - the reference implementation of the pattern every VanillaBP adapter uses on
 * Spring Boot to contribute its own keys (e.g. connection settings) to the canonical
 * per-adapter location <code>vanillabp.adapters.&lt;id&gt;.*</code>: a second
 * {@code @ConfigurationProperties("vanillabp")} class coexists with the platform's
 * binding of the core model (same-prefix classes bind side by side; keys unknown to
 * either view are ignored by the JavaBean binding).
 * <p>
 * The adapter-id set is NEVER derived from this overlay map - it always comes from
 * the platform's core properties ({@code MigrationAdapterProperties.adapterTypes()});
 * the overlay is a per-known-id lookup only (environment-variable overrides can
 * materialize phantom map entries in the overlay).
 */
@ConfigurationProperties(MigrationAdapterProperties.PREFIX)
@Getter
@Setter
public class DummyAdapterOverlayProperties {

  /**
   * The adapter sections of the shared tree, keyed by adapter ID - only the dummy
   * adapter's own keys are modeled here.
   */
  private Map<String, DummyAdapterConfig> adapters = Map.of();

  /**
   * The workflow-module sections of the shared tree, keyed by workflow module ID -
   * the overlay mirrors the levels of the most-specific-wins resolution of
   * adapter-scoped properties (task &gt; workflow &gt; workflow-module &gt;
   * adapter), so scope-specific adapter keys (like a per-task job timeout of a real
   * BPMS) resolve from real application configuration.
   */
  private Map<String, ModuleOverlay> workflowModules = Map.of();

  /**
   * Resolves the dummy adapter's <code>test</code> key with most-specific-wins
   * semantics across the four levels - the reference implementation of how a real
   * adapter resolves its scope-specific keys from its overlay (the core's
   * <code>resolveForAdapter</code> covers the core-owned keys; overlay keys are
   * walked by the adapter itself, same order).
   *
   * @param workflowModuleId The workflow module ID or <code>null</code>
   * @param bpmnProcessId The BPMN process ID or <code>null</code>
   * @param taskId The task ID (task definition) or <code>null</code>
   * @param adapterId The adapter ID
   * @return The most specific configured value or <code>null</code>
   */
  public Integer testFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskId,
      final String adapterId) {

    final var module = workflowModuleId != null
        ? workflowModules.get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module.getWorkflows().get(bpmnProcessId)
        : null;
    final var task = (workflow != null) && (taskId != null)
        ? workflow.getTasks().get(taskId)
        : null;

    final var levelsMostSpecificFirst = new java.util.LinkedList<Map<String, DummyAdapterConfig>>();
    if (task != null) {
      levelsMostSpecificFirst.add(task.getAdapters());
    }
    if (workflow != null) {
      levelsMostSpecificFirst.add(workflow.getAdapters());
    }
    if (module != null) {
      levelsMostSpecificFirst.add(module.getAdapters());
    }
    levelsMostSpecificFirst.add(adapters);
    return levelsMostSpecificFirst
        .stream()
        .map(level -> level.get(adapterId))
        .filter(java.util.Objects::nonNull)
        .map(DummyAdapterConfig::getTest)
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(null);

  }

  /**
   * The dummy adapter's keys of one <code>vanillabp.adapters.&lt;id&gt;</code>
   * section.
   */
  @Getter
  @Setter
  public static class DummyAdapterConfig {

    /**
     * A test value used by the platform integration's tests to prove that
     * adapter-specific keys inside the shared tree are tolerated and reach the
     * adapter's overlay typed.
     */
    private Integer test;

  }

  /**
   * The dummy adapter's view of one workflow-module section.
   */
  @Getter
  @Setter
  public static class ModuleOverlay {

    private Map<String, DummyAdapterConfig> adapters = Map.of();

    private Map<String, WorkflowOverlay> workflows = Map.of();

  }

  /**
   * The dummy adapter's view of one workflow section.
   */
  @Getter
  @Setter
  public static class WorkflowOverlay {

    private Map<String, DummyAdapterConfig> adapters = Map.of();

    private Map<String, TaskOverlay> tasks = Map.of();

  }

  /**
   * The dummy adapter's view of one task section - the MOST specific level.
   */
  @Getter
  @Setter
  public static class TaskOverlay {

    private Map<String, DummyAdapterConfig> adapters = Map.of();

  }

}
