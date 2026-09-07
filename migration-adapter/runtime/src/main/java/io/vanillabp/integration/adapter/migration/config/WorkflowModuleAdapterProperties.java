package io.vanillabp.integration.adapter.migration.config;

import java.util.Map;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class WorkflowModuleAdapterProperties extends AdaptersConfigurationProperties {

  String workflowModuleId;

  @Builder.Default
  private Map<String, AdapterProperties> adapters = Map.of();

  /**
   * The workflows of the workflow module. The key is the BPMN process ID.
   * <p>
   * <i>Hint:</i> Back-references (BPMN process ID, workflow module) are linked by
   * {@link MigrationAdapterProperties#validateAndLink()}.
   */
  @Builder.Default
  private Map<String, WorkflowAdapterProperties> workflows = Map.of();

  /**
   * Overrides <code>vanillabp.transactions</code> for this workflow module. A setting
   * left undefined here means the global one applies, so a single module can accept
   * unguarded writes while every other one keeps failing the startup check.
   */
  private TransactionsProperties transactions;

  /**
   * Overrides <code>vanillabp.election</code> for this workflow module. A setting
   * left out here means "whatever is configured globally".
   */
  private ElectionProperties election;

  /**
   * Overrides <code>vanillabp.delivery</code> for this workflow module. A setting left
   * undefined here means the global one applies, so one module can release the records of
   * its ended workflows while another keeps them for support.
   */
  private DeliveryProperties delivery;

  /**
   * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this workflow
   * module - the place an extension configured once for the whole application says
   * something different about one of its modules (the Business Cockpit's URI of a module,
   * say). Keys are the extension ids.
   */
  @Builder.Default
  private Map<String, Map<String, String>> extensions = Map.of();

}
