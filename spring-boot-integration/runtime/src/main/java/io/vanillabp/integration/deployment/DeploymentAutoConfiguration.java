package io.vanillabp.integration.deployment;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.deployment.DeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.integration.workflowmodule.WorkflowModules;
import io.vanillabp.spi.process.ProcessService;
import lombok.extern.slf4j.Slf4j;

/**
 * Autoconfiguration of VanillaBP's deployment service.
 */
@Slf4j
@AutoConfiguration(after = {
    WorkflowModuleAutoConfiguration.class, SpringBootMigrationAdapterAutoConfiguration.class
})
@ConditionalOnBean({
    WorkflowModules.class, MigrationAdapterProperties.class
})
public class DeploymentAutoConfiguration {

  static final String BEANNAME_DEPLOYMENTSERVICE = "VanillaBpDeploymentService";

  /**
   * Collects the adapters' deployment services and the extensions' wiring services
   * via {@link ObjectProvider} streams: the convention is one <i>element</i> bean
   * per adapter/extension (never a bean of type <code>List&lt;...&gt;</code>) so
   * multiple adapter types coexist in one application - the central migration
   * scenario. Since {@link AdapterDeploymentService} extends
   * {@link ExtensionWiringService}, the wiring stream contains the adapters, too;
   * the core {@link DeploymentService} filters them out (adapters are wired
   * explicitly by the deployment pipeline).
   */
  @Bean(BEANNAME_DEPLOYMENTSERVICE)
  public SpringBootDeploymentService deploymentService(
      final WorkflowModules allWorkflowModules,
      final MigrationAdapterProperties properties,
      final ObjectProvider<AdapterDeploymentService<?, ?>> deploymentServiceProvider,
      final ObjectProvider<ExtensionWiringService<?, ?>> wiringServiceProvider,
      final ObjectProvider<ProcessService<?>> processServices,
      final io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring workflowTaskWiring,
      final org.springframework.core.io.ResourceLoader resourceLoader) {

    final List<AdapterDeploymentService<?, ?>> deploymentServices = deploymentServiceProvider
        .stream()
        .toList();
    final List<ExtensionWiringService<?, ?>> wiringServices = wiringServiceProvider
        .stream()
        .toList();

    // the wiring interface goes in as well: what belongs to a workflow module as a
    // whole is the core's own duty, not an adapter's - the deployed processes no
    // @WorkflowService class claims, the versions a BPMS still holds for a process id
    // the application only declares, the @WorkflowTask methods serving no task at all
    // and the version tags the annotations name
    // and one contributor to the report about a process nothing claims: on Spring Boot a
    // workflow service is found because it is a bean, so a class carrying the annotation
    // without one is invisible to the discovery and only a scan of class resources can name
    // it. That scan runs where such a process is being reported and nowhere else
    final var deploymentService = new DeploymentService(
        properties, deploymentServices, wiringServices, workflowTaskWiring, new io.vanillabp.integration.processservice.WorkflowServicesWhichAreNoBeans(
            resourceLoader, allWorkflowModules));

    return new SpringBootDeploymentService(
        deploymentService, allWorkflowModules, processServices);

  }

}
