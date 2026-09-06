package io.vanillabp.bpmsdouble.quarkus;

import java.util.Map;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.quarkus.runtime.annotations.StaticInitSafe;
import io.smallrye.config.ConfigMapping;

/**
 * The dummy adapter's OVERLAY of the shared <code>vanillabp.*</code> configuration
 * tree - the reference implementation of the pattern every VanillaBP adapter
 * extension uses to contribute its own keys (e.g. connection settings) to the
 * canonical per-adapter location <code>vanillabp.adapters.&lt;id&gt;.*</code>:
 * <ul>
 *   <li>a second RUN_TIME {@code @ConfigMapping} over the same prefix coexists with
 *       the platform's mapping - overlapping keys are served to both;</li>
 *   <li>SmallRye's unknown-key validation passes if ANY registered mapping knows a
 *       key, so platform keys ({@code type}, {@code deployment-failure}) and
 *       adapter keys ({@code test}) validate each other's sections - no blanket
 *       {@code withMappingIgnore} needed (a typo under {@code vanillabp.*} fails
 *       the startup again);</li>
 *   <li>the adapter-id set is NEVER derived from this overlay map - it always
 *       comes from the platform's core properties
 *       ({@code MigrationAdapterProperties.adapterTypes()}), the overlay is a
 *       per-known-id lookup only.</li>
 * </ul>
 */
@StaticInitSafe
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
@ConfigMapping(prefix = "vanillabp")
public interface DummyAdapterOverlayProperties {

  /**
   * The adapter sections of the shared tree, keyed by adapter ID - only the dummy
   * adapter's own keys are modeled here.
   */
  Map<String, DummyAdapterConfig> adapters();

  /**
   * The workflow-module sections of the shared tree, keyed by workflow module ID -
   * the overlay mirrors the levels of the most-specific-wins resolution of
   * adapter-scoped properties (task &gt; workflow &gt; workflow-module &gt;
   * adapter), so scope-specific adapter keys (like a per-task job timeout of a
   * real BPMS) resolve from real application configuration.
   */
  Map<String, ModuleOverlay> workflowModules();

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
  default Integer testFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskId,
      final String adapterId) {

    final var module = workflowModuleId != null
        ? workflowModules().get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module.workflows().get(bpmnProcessId)
        : null;
    final var task = (workflow != null) && (taskId != null)
        ? workflow.tasks().get(taskId)
        : null;

    final var levelsMostSpecificFirst = new java.util.LinkedList<Map<String, DummyAdapterConfig>>();
    if (task != null) {
      levelsMostSpecificFirst.add(task.adapters());
    }
    if (workflow != null) {
      levelsMostSpecificFirst.add(workflow.adapters());
    }
    if (module != null) {
      levelsMostSpecificFirst.add(module.adapters());
    }
    levelsMostSpecificFirst.add(adapters());
    return levelsMostSpecificFirst
        .stream()
        .map(level -> level.get(adapterId))
        .filter(java.util.Objects::nonNull)
        .map(DummyAdapterConfig::test)
        .flatMap(Optional::stream)
        .findFirst()
        .orElse(null);

  }

  /**
   * The dummy adapter's keys of one <code>vanillabp.adapters.&lt;id&gt;</code>
   * section.
   */
  interface DummyAdapterConfig {

    /**
     * A test value used by the platform integration's tests to prove that
     * adapter-specific keys inside the shared tree are tolerated and reach the
     * adapter's overlay typed.
     */
    Optional<Integer> test();

  }

  /**
   * The dummy adapter's view of one workflow-module section.
   */
  interface ModuleOverlay {

    /**
     * The module-level adapter sections, keyed by adapter ID.
     *
     * @return The adapter sections
     */
    Map<String, DummyAdapterConfig> adapters();

    /**
     * The workflow sections of the module, keyed by BPMN process ID.
     *
     * @return The workflow sections
     */
    Map<String, WorkflowOverlay> workflows();

  }

  /**
   * The dummy adapter's view of one workflow section.
   */
  interface WorkflowOverlay {

    /**
     * The workflow-level adapter sections, keyed by adapter ID.
     *
     * @return The adapter sections
     */
    Map<String, DummyAdapterConfig> adapters();

    /**
     * The task sections of the workflow, keyed by task ID (task definition).
     *
     * @return The task sections
     */
    Map<String, TaskOverlay> tasks();

  }

  /**
   * The dummy adapter's view of one task section - the MOST specific level.
   */
  interface TaskOverlay {

    /**
     * The task-level adapter sections, keyed by adapter ID.
     *
     * @return The adapter sections
     */
    Map<String, DummyAdapterConfig> adapters();

  }

}
