package io.vanillabp.integration.adapter.migration.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The settings of one extension: everything below
 * <code>vanillabp.extensions.&lt;extension&gt;</code> globally and below
 * <code>vanillabp.workflow-modules.&lt;id&gt;.extensions.&lt;extension&gt;</code> per
 * workflow module, keyed by the rest of the path
 * (<code>rest.base-url</code>).
 * <p>
 * VanillaBP deliberately does not know what the keys mean - an extension binds and
 * validates its own, typed, the way an adapter binds the keys below its adapter id. What
 * the core owns is the LOCATION, so every extension is configured in one place and
 * beside the rest of the setup, and the resolution of the two levels
 * ({@link MigrationAdapterProperties#extensionProperties(String, String)}).
 */
public final class ExtensionProperties {

  private ExtensionProperties() {
  }

  /**
   * Merges a workflow module's settings over the global ones, per key: a module which
   * says something about one key keeps whatever the global section says about the rest.
   * That is the same most-specific-wins rule an adapter setting follows, with two levels
   * instead of four.
   *
   * @param global The settings below <code>vanillabp.extensions.&lt;extension&gt;</code>
   * @param ofWorkflowModule The settings below
   *          <code>vanillabp.workflow-modules.&lt;id&gt;.extensions.&lt;extension&gt;</code>
   * @return The merged settings, never <code>null</code>
   */
  public static Map<String, String> merge(
      final Map<String, String> global,
      final Map<String, String> ofWorkflowModule) {

    if ((ofWorkflowModule == null) || ofWorkflowModule.isEmpty()) {
      return (global == null) || global.isEmpty()
          ? Map.of()
          : Map.copyOf(global);
    }
    if ((global == null) || global.isEmpty()) {
      return Map.copyOf(ofWorkflowModule);
    }

    final var merged = new LinkedHashMap<String, String>(global);
    merged.putAll(ofWorkflowModule);
    return Map.copyOf(merged);

  }

}
