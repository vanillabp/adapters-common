package io.vanillabp.integration.adapter;

import java.util.function.Consumer;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.config.VanillaBpConfigurationProperties;

/**
 * Support for BPMS adapters registering their per-adapter-id beans on Spring Boot:
 * for each configured adapter id of the adapter's type, ONE
 * {@code MigratableProcessService} element bean and ONE
 * {@code AdapterDeploymentService} element bean have to be registered (multiple ids
 * of the same BPMS type are the migration scenario) - never a bean of type
 * {@code List<...>}: the platform collects element beans via
 * {@code ObjectProvider.stream()}.
 * <p>
 * Since the id set comes from the runtime configuration, the beans have to be
 * registered programmatically (a {@code BeanRegistrar}) - configuration-properties
 * beans are not bound yet at registration time, so this helper binds the core
 * <code>vanillabp.*</code> tree directly off the {@link Environment} (workflow-module
 * config files are ordinary property sources added by an
 * {@code EnvironmentPostProcessor}, so they are visible here). Usage in an adapter's
 * {@code BeanRegistrar}:
 *
 * <pre>
 * &#64;Override
 * public void register(final BeanRegistry registry, final Environment environment) {
 *   AdapterBeanRegistrarSupport.forEachConfiguredAdapterId(environment, ADAPTER_TYPE,
 *       adapterId -&gt; registry.registerBean("MyAdapter_ProcessService_" + adapterId,
 *           MigratableProcessService.class,
 *           spec -&gt; spec.supplier(supplierContext -&gt; ...)));
 * }
 * </pre>
 *
 * The adapter-id set ALWAYS comes from the platform's core properties (this helper);
 * adapter-owned overlay maps of the <code>vanillabp.*</code> tree are per-known-id
 * lookups only and must never be iterated to discover ids (environment-variable
 * overrides can materialize phantom map entries in overlays).
 */
public final class AdapterBeanRegistrarSupport {

  private AdapterBeanRegistrarSupport() {
  }

  /**
   * Invokes the given consumer for each adapter id configured in
   * <code>vanillabp.adapters.&lt;id&gt;.*</code> whose (defaulted) type equals the
   * given adapter type, in stable (sorted) order.
   * <p>
   * WHICH ids those are is
   * {@link MigrationAdapterProperties#adapterIdsOfType(String)} - the rule lives in the
   * core because Quarkus has to answer the same question, and because an extension
   * bridging to a BPMS asks it about the same configuration. What is left here is
   * binding the tree off the environment, which is the part only Spring Boot can do.
   *
   * @param environment The Spring environment to bind the core properties from
   * @param adapterType The adapter's type
   * @param adapterIdConsumer Invoked once per configured adapter id of the type
   */
  public static void forEachConfiguredAdapterId(
      final Environment environment,
      final String adapterType,
      final Consumer<String> adapterIdConsumer) {

    Binder
        .get(environment)
        .bind(MigrationAdapterProperties.PREFIX, Bindable.of(VanillaBpConfigurationProperties.class))
        .orElseGet(VanillaBpConfigurationProperties::new)
        .adapterIdsOfType(adapterType)
        .forEach(adapterIdConsumer);

  }

  /**
   * Collects what this platform hands to an adapter, so that a registrar asks for the
   * set instead of remembering the individual beans. The two collaborators an
   * application may not have are looked up as providers and stay absent if no bean
   * exists - which {@link AdapterCollaborators} reports naming the adapter id.
   *
   * @param supplierContext The context of the bean being registered
   * @param adapterId The adapter id the beans are registered for
   * @return The collaborators, complete
   * @see AdapterCollaborators
   */
  public static AdapterCollaborators collaborators(
      final org.springframework.beans.factory.BeanRegistry.SupplierContext supplierContext,
      final String adapterId) {

    return AdapterCollaborators
        .forAdapter(adapterId)
        .workflowTaskWiring(supplierContext.bean(WorkflowTaskWiring.class))
        .workflowTaskInvoker(supplierContext.bean(WorkflowTaskInvoker.class))
        .scoping(supplierContext.bean(NameClashAvoidanceSupport.class))
        .workflowAggregateSync(supplierContext.bean(WorkflowAggregateSync.class))
        .preCommitRegistrar(supplierContext.bean(PreCommitRegistrar.class))
        .workflowEndedInvoker(supplierContext.beanProvider(WorkflowEndedInvoker.class).getIfAvailable())
        .bpmsInitiatedStartInvoker(
            supplierContext.beanProvider(BpmsInitiatedStartInvoker.class).getIfAvailable())
        .build();

  }

}
