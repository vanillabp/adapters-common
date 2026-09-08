package io.vanillabp.integration.deployment;

import java.net.URI;
import java.util.List;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.AdditionalApplicationArchiveMarkerBuildItem;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.ObjectSubstitutionBuildItem;
import io.vanillabp.integration.deployment.processservice.VanillaBpMigratableProcessServiceBuildItem;
import io.vanillabp.integration.runtime.deployment.VanillaBpShutdownObserver;
import io.vanillabp.integration.runtime.mongo.MongoClientDeploymentProbe;
import io.vanillabp.integration.runtime.outbox.JdbcPhaseTwoOutbox;
import io.vanillabp.integration.runtime.outbox.JdbcPhaseTwoOutboxDispatcher;
import io.vanillabp.integration.runtime.outbox.MongoPhaseTwoOutbox;
import io.vanillabp.integration.runtime.outbox.MongoPhaseTwoOutboxDispatcher;
import io.vanillabp.integration.runtime.processservice.PhaseTwoOutboxResolverProducer;
import io.vanillabp.integration.runtime.processservice.PhaseTwoRouterProducer;
import io.vanillabp.integration.runtime.processservice.QuarkusPreCommitRegistrar;
import io.vanillabp.integration.runtime.processservice.WorkflowAdapterCacheProducer;
import io.vanillabp.integration.runtime.util.UriSubstitute;
import io.vanillabp.integration.runtime.util.UriSubstitution;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.runtime.workflowtask.WorkflowTaskRegistryProducer;

/**
 * Main VanillaBP extension processor, responsible for processing configuration
 * and the projects classes during the augmentation phase.
 */
public class VanillaBpBuildStepProcessor {

  /**
   * The VanillaBP extensions feature.
   */
  private static final String FEATURE = "vanillabp";

  /**
   * Build extension feature used as a dependency in VanillaBP adapter extensions.
   *
   * @return The feature build item
   */
  @BuildStep
  FeatureBuildItem buildExtensionFeature() {

    return new FeatureBuildItem(FEATURE);

  }

  /**
   * Registers the workflow-module descriptor as an application-archive marker. This way
   * workflow-module JARs are treated as application archives even if they do not
   * provide a Jandex index (e.g. Gradle modules or JARs containing only the descriptor
   * and BPMS resources). A Jandex index remains necessary only for discovery of
   * {@link io.vanillabp.spi.service.WorkflowService} annotated classes inside
   * sub-modules, not for workflow-module detection itself.
   *
   * @return The additional application-archive marker build item
   */
  @BuildStep
  AdditionalApplicationArchiveMarkerBuildItem workflowModuleArchiveMarker() {

    return new AdditionalApplicationArchiveMarkerBuildItem(WorkflowModule.METAINF_WORKFLOWMODULE);

  }

  /**
   * If any serialization of Quarkus needs to serialize an object straight forward to serialize,
   * an ObjectSubstitutionBuildItem needs to be provided for proper serialization and deserialization.
   *
   * @see io.vanillabp.integration.deployment.workflowmodule.WorkflowModuleBuildStepProcessor#findAllWorkflowModules(ApplicationArchivesBuildItem)
   * @return An object substitution build item for {@link URI}
   */
  @BuildStep
  ObjectSubstitutionBuildItem uriSubstitution() {

    return new ObjectSubstitutionBuildItem(URI.class, UriSubstitute.class, UriSubstitution.class);

  }

  /**
   * Registers the adapters' process-service beans announced via
   * {@link VanillaBpMigratableProcessServiceBuildItem}: adapters only produce the
   * build item (adapter type + bean class), the VanillaBP extension registers the
   * bean - no separate self-registration needed.
   *
   * @param processServicesProvidedByAdapters The build items produced by the adapters
   * @param additionalBeans Producer used to register the announced beans
   */
  @BuildStep
  void buildAdapterProcessServiceBeans(
      final List<VanillaBpMigratableProcessServiceBuildItem> processServicesProvidedByAdapters,
      final BuildProducer<AdditionalBeanBuildItem> additionalBeans) {

    processServicesProvidedByAdapters
        .stream()
        .map(VanillaBpMigratableProcessServiceBuildItem::getMigratableProcessServiceBeanClass)
        .filter(beanClass -> (beanClass != null) && !beanClass.isBlank())
        .forEach(beanClass -> additionalBeans.produce(AdditionalBeanBuildItem
            .builder()
            .addBeanClass(beanClass)
            .setUnremovable() // don't remove, since it is used under the hoods
            .build()));

  }

  /**
   * Keeps the adapters' per-adapter-id List beans from being removed: adapters
   * produce ONE bean of type <code>List&lt;MigratableProcessService&gt;</code> /
   * <code>List&lt;AdapterDeploymentService&gt;</code> with one instance per
   * configured adapter id (a CDI producer cannot yield N element beans for N
   * runtime-configured ids). The platform collects them via
   * <code>Instance&lt;List&lt;...&gt;&gt;</code> lookups only - without this build
   * item ArC treats the producers as unused and removes them.
   * <p>
   * Convention (part of the per-adapter-id contract): the List's element type is
   * the SPI interface itself (e.g.
   * <code>List&lt;MigratableProcessService&lt;Object&gt;&gt;</code>), not an
   * adapter-specific subclass - the type is matched literally here.
   *
   * @return The unremovable-bean build item
   */
  @BuildStep
  io.quarkus.arc.deployment.UnremovableBeanBuildItem keepPerAdapterIdListBeans() {

    final var listName = org.jboss.jandex.DotName.createSimple(List.class.getName());
    final var elementTypes = java.util.Set.of(
        org.jboss.jandex.DotName.createSimple(
            io.vanillabp.integration.adapter.spi.MigratableProcessService.class.getName()),
        org.jboss.jandex.DotName.createSimple(
            io.vanillabp.integration.adapter.spi.AdapterDeploymentService.class.getName()));

    return new io.quarkus.arc.deployment.UnremovableBeanBuildItem(
        beanInfo -> beanInfo
            .getTypes()
            .stream()
            .anyMatch(type -> (type.kind() == org.jboss.jandex.Type.Kind.PARAMETERIZED_TYPE) && type.name()
                .equals(listName) && (type.asParameterizedType().arguments().size() == 1) && elementTypes
                    .contains(type.asParameterizedType().arguments().getFirst().name())));

  }

  /**
   * Registers the core-owned phase-two router (via {@link PhaseTwoRouterProducer}):
   * the generated process-service beans register themselves with it at bean
   * creation, and the phase-two outbox dispatches committed entries through it. It
   * is registered independently of any outbox implementation, so custom
   * <code>PhaseTwoOutbox</code> beans can rely on it, too.
   *
   * @return The additional {@link PhaseTwoRouterProducer} bean
   */
  @BuildStep
  AdditionalBeanBuildItem buildPhaseTwoRouter() {

    return AdditionalBeanBuildItem
        .builder()
        .addBeanClass(PhaseTwoRouterProducer.class)
        .setUnremovable() // don't remove, since it is used under the hoods
        .build();

  }

  /**
   * Registers the resolver telling which phase-two outbox an aggregate's transaction
   * reaches (via {@link PhaseTwoOutboxResolverProducer}): the process services resolve
   * their own outbox through it, and an extension writing entries of its own injects it
   * instead of picking an outbox bean, which it cannot judge.
   *
   * @return The additional {@link PhaseTwoOutboxResolverProducer} bean
   */
  @BuildStep
  AdditionalBeanBuildItem buildPhaseTwoOutboxResolver() {

    return AdditionalBeanBuildItem
        .builder()
        .addBeanClass(PhaseTwoOutboxResolverProducer.class)
        .setUnremovable() // an extension may be the only one injecting it
        .build();

  }

  /**
   * Registers the default in-memory election cache (workflow&rarr;adapter
   * associations of the BPMS election, see the core's
   * <code>WorkflowLocator</code>). An application-provided
   * <code>WorkflowAdapterCache</code> bean replaces the default (it is a
   * <code>&#64;DefaultBean</code>).
   *
   * @return The additional {@link WorkflowAdapterCacheProducer} bean
   */
  @BuildStep
  AdditionalBeanBuildItem buildWorkflowAdapterCache() {

    return AdditionalBeanBuildItem
        .builder()
        .addBeanClass(WorkflowAdapterCacheProducer.class)
        .setUnremovable() // resolved via Instance lookup only
        .build();

  }

  /**
   * Registers the core-owned registry of <code>&#64;WorkflowTask</code> handlers
   * (the adapter-facing
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker}):
   * the generated process-service beans register every workflow service class under
   * all BPMN process IDs it declares; adapters validate the wiring during
   * <code>wireBpmn</code> and dispatch task invocations at runtime.
   *
   * @return The additional {@link WorkflowTaskRegistryProducer} bean
   */
  @BuildStep
  AdditionalBeanBuildItem buildWorkflowTaskRegistry() {

    return AdditionalBeanBuildItem
        .builder()
        .addBeanClass(WorkflowTaskRegistryProducer.class)
        .setUnremovable() // don't remove, since it is used under the hoods
        .build();

  }

  /**
   * Registers the default implementations of the phase-two outbox (see
   * {@link io.vanillabp.integration.spi.PhaseTwoOutbox}) used for two-phase
   * workflow starts: the JDBC/Agroal-based one if a JDBC datasource applies AND the
   * MongoDB-based one if the <code>quarkus-mongodb-client</code> extension is
   * present - both may coexist, each workflow aggregate is served by the outbox
   * matching its persistence (attribution via
   * {@link io.vanillabp.integration.spi.PhaseTwoOutboxAware} beans, see
   * {@link io.vanillabp.integration.runtime.processservice.QuarkusPhaseTwoOutboxResolver}).
   * Unwanted defaults can be deactivated via
   * <code>vanillabp.outbox.jdbc.enabled</code> /
   * <code>vanillabp.outbox.mongo.enabled</code>. Applications may always provide
   * their own <code>PhaseTwoOutbox</code> beans in addition.
   *
   * @param capabilities Capabilities of the project's extensions
   * @param additionalBeans Producer used to register the outbox beans
   */
  @BuildStep
  void buildPhaseTwoOutbox(
      final Capabilities capabilities,
      final BuildProducer<AdditionalBeanBuildItem> additionalBeans) {

    if (capabilities.isPresent(Capability.AGROAL)) {
      additionalBeans.produce(AdditionalBeanBuildItem
          .builder()
          .addBeanClasses(
              JdbcPhaseTwoOutbox.class,
              JdbcPhaseTwoOutboxDispatcher.class)
          .setUnremovable() // don't remove, since it is used under the hoods
          .build());
    }

    if (capabilities.isPresent(Capability.MONGODB_CLIENT)) {
      additionalBeans.produce(AdditionalBeanBuildItem
          .builder()
          .addBeanClasses(
              MongoPhaseTwoOutbox.class,
              MongoPhaseTwoOutboxDispatcher.class)
          .setUnremovable() // don't remove, since it is used under the hoods
          .build());
    }

  }

  /**
   * Registers the probe telling whether the MongoDB deployment is a replica set (see
   * {@link io.vanillabp.integration.runtime.processservice.MongoDeploymentProbe}), which
   * the transaction-runner startup check needs for an aggregate MongoDB Panache manages.
   * <p>
   * Guarded by the MongoDB client capability like the outbox and the delivery log, and for
   * one more reason than they have: the implementation is the only class on the way to the
   * coverage verdict which names a MongoDB type. A native image resolves every referenced
   * method while it is built, so an application with a relational database and no MongoDB
   * must not reach it at all.
   *
   * @param capabilities Capabilities of the project's extensions
   * @param additionalBeans Producer used to register the probe
   */
  @BuildStep
  void buildMongoDeploymentProbe(
      final Capabilities capabilities,
      final BuildProducer<AdditionalBeanBuildItem> additionalBeans) {

    if (!capabilities.isPresent(Capability.MONGODB_CLIENT)) {
      return;
    }
    additionalBeans.produce(AdditionalBeanBuildItem
        .builder()
        .addBeanClass(MongoClientDeploymentProbe.class)
        .setUnremovable() // don't remove, since it is looked up under the hoods
        .build());

  }

  /**
   * Registers the default implementations of the log of processed task deliveries (see
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog}): the JDBC/Agroal-based one if a
   * JDBC datasource applies AND the MongoDB-based one if the
   * <code>quarkus-mongodb-client</code> extension is present - both may coexist, each
   * workflow aggregate is served by the log matching its persistence (attribution via
   * {@link io.vanillabp.integration.spi.TaskDeliveryLogAware} beans, see
   * {@link io.vanillabp.integration.runtime.processservice.QuarkusTaskDeliveryLogResolver}).
   * Unwanted defaults can be deactivated via
   * <code>vanillabp.outbox.jdbc.enabled</code> /
   * <code>vanillabp.outbox.mongo.enabled</code> - the log shares the outbox' store
   * settings.
   *
   * @param capabilities Capabilities of the project's extensions
   * @param additionalBeans Producer used to register the delivery-log beans
   */
  @BuildStep
  void buildTaskDeliveryLog(
      final Capabilities capabilities,
      final BuildProducer<AdditionalBeanBuildItem> additionalBeans) {

    if (capabilities.isPresent(Capability.AGROAL)) {
      additionalBeans.produce(AdditionalBeanBuildItem
          .builder()
          .addBeanClasses(io.vanillabp.integration.runtime.delivery.JdbcTaskDeliveryLog.class)
          .setUnremovable() // don't remove, since it is used under the hoods
          .build());
    }

    if (capabilities.isPresent(Capability.MONGODB_CLIENT)) {
      additionalBeans.produce(AdditionalBeanBuildItem
          .builder()
          .addBeanClasses(io.vanillabp.integration.runtime.delivery.MongoTaskDeliveryLog.class)
          .setUnremovable() // don't remove, since it is used under the hoods
          .build());
    }

  }

  /**
   * Beans implementing {@link io.vanillabp.integration.spi.TaskDeliveryLog} or
   * {@link io.vanillabp.integration.spi.TaskDeliveryLogAware} are looked up dynamically
   * at runtime (per-aggregate log resolution), not injected by application code - ArC
   * must not remove them as unused.
   *
   * @return The unremovable-bean build item covering delivery logs and their attributions
   */
  @BuildStep
  io.quarkus.arc.deployment.UnremovableBeanBuildItem preserveTaskDeliveryLogBeans() {

    return io.quarkus.arc.deployment.UnremovableBeanBuildItem.beanTypes(
        io.vanillabp.integration.spi.TaskDeliveryLog.class,
        io.vanillabp.integration.spi.TaskDeliveryLogAware.class);

  }

  /**
   * Beans implementing {@link io.vanillabp.integration.spi.PhaseTwoOutbox}
   * or {@link io.vanillabp.integration.spi.PhaseTwoOutboxAware} are not
   * necessarily injected by application code but looked up dynamically at runtime
   * (per-aggregate outbox resolution). This build step prevents ArC from removing
   * them as unused beans.
   *
   * @return The unremovable-bean build item covering outboxes and their attributions
   */
  @BuildStep
  io.quarkus.arc.deployment.UnremovableBeanBuildItem preservePhaseTwoOutboxBeans() {

    return io.quarkus.arc.deployment.UnremovableBeanBuildItem.beanTypes(
        io.vanillabp.integration.spi.PhaseTwoOutbox.class,
        io.vanillabp.integration.spi.PhaseTwoOutboxAware.class);

  }

  /**
   * Registers {@link VanillaBpShutdownObserver} as a CDI bean: it stops workflow
   * processing of adapters and extensions on graceful shutdown (the Quarkus
   * counterpart of the Spring Boot integration's <code>SmartLifecycle.stop()</code>).
   * It is marked unremovable because it is not injected by application code but
   * driven by the {@link io.quarkus.runtime.ShutdownEvent}.
   *
   * @return The additional {@link VanillaBpShutdownObserver} bean
   */
  @BuildStep
  AdditionalBeanBuildItem buildShutdownObserver() {

    return AdditionalBeanBuildItem
        .builder()
        .addBeanClass(VanillaBpShutdownObserver.class)
        .setUnremovable() // don't remove, since it is used under the hoods
        .build();

  }

  /**
   * Registers {@link QuarkusPreCommitRegistrar} as a CDI bean: BPMS adapters
   * hand their phase-one checks to it, so the check runs right before the transaction of
   * the workflow aggregate commits. Unremovable, because no application code injects it.
   *
   * @return The additional {@link QuarkusPreCommitRegistrar} bean
   */
  @BuildStep
  AdditionalBeanBuildItem buildPreCommitRegistrar() {

    return AdditionalBeanBuildItem
        .builder()
        .addBeanClass(QuarkusPreCommitRegistrar.class)
        .setUnremovable() // don't remove, since it is used under the hoods
        .build();

  }

}
