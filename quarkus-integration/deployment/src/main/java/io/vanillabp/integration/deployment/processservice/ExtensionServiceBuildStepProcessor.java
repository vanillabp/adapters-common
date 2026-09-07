package io.vanillabp.integration.deployment.processservice;

import java.util.LinkedHashSet;

import org.jboss.jandex.ClassType;
import org.jboss.jandex.DotName;
import org.jboss.jandex.ParameterizedType;
import org.jboss.jandex.Type;

import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.vanillabp.integration.extension.spi.service.AggregateServiceFactory;
import io.vanillabp.integration.runtime.processservice.ExtensionServiceRecorder;

/**
 * Gives an extension the same per-aggregate bean VanillaBP gives itself: an application
 * injecting <code>TheService&lt;TheAggregate&gt;</code> gets what the extension's
 * {@link AggregateServiceFactory} builds for that aggregate.
 * <p>
 * One synthetic bean per (service interface, workflow aggregate) is registered, typed
 * with the aggregate as its type argument so the injection point resolves. The beans are
 * created on first use, which is what makes injecting them optional - unlike
 * {@code ProcessService}, nothing here demands the injection point.
 */
public class ExtensionServiceBuildStepProcessor {

  /**
   * @param combinedIndex The index, asked which classes implement
   *          {@link AggregateServiceFactory}
   * @param workflowAggregates The workflow aggregates of this application
   * @param recorder Builds a service when its bean is created
   * @param syntheticBeanProducer Collects the beans
   */
  @BuildStep
  @Record(ExecutionTime.RUNTIME_INIT)
  void buildExtensionServices(
      final CombinedIndexBuildItem combinedIndex,
      final VanillaBpWorkflowAggregatesBuildItem workflowAggregates,
      final ExtensionServiceRecorder recorder,
      final BuildProducer<SyntheticBeanBuildItem> syntheticBeanProducer) {

    final var serviceInterfaces = serviceInterfacesOf(combinedIndex);
    if (serviceInterfaces.isEmpty()) {
      return;
    }

    serviceInterfaces
        .forEach(serviceInterface -> workflowAggregates
            .getWorkflowAggregateClasses()
            .forEach(workflowAggregateClass -> syntheticBeanProducer
                .produce(SyntheticBeanBuildItem
                    .configure(serviceInterface)
                    .types(ParameterizedType
                        .create(
                            serviceInterface,
                            new Type[]{
                                ClassType.create(workflowAggregateClass)
                            },
                            null))
                    .scope(jakarta.inject.Singleton.class)
                    .unremovable()
                    .setRuntimeInit()
                    .createWith(
                        recorder
                            .extensionService(serviceInterface.toString(), workflowAggregateClass.toString()))
                    .done())));

  }

  /**
   * The service interfaces the extensions of this application offer, read off the
   * {@link AggregateServiceFactory} implementations in the index.
   *
   * @param combinedIndex The index
   * @return The interfaces, each of them once
   * @throws IllegalStateException If a factory names a service interface which cannot
   *           carry the aggregate as its type argument
   */
  private static java.util.Set<DotName> serviceInterfacesOf(
      final CombinedIndexBuildItem combinedIndex) {

    final var factoryInterface = DotName.createSimple(AggregateServiceFactory.class.getName());
    final var serviceInterfaces = new LinkedHashSet<DotName>();
    for (final var implementation : combinedIndex
        .getIndex()
        .getAllKnownImplementations(factoryInterface)) {
      final var declared = implementation
          .interfaceTypes()
          .stream()
          .filter(type -> factoryInterface.equals(type.name()))
          .filter(ParameterizedType.class::isInstance)
          .map(ParameterizedType.class::cast)
          .findFirst()
          .orElseThrow(() -> new IllegalStateException(
              """
                  The AggregateServiceFactory '%s' does not name the service interface it builds! \
                  Implement AggregateServiceFactory<YourService> rather than the raw interface - \
                  VanillaBP registers one bean of that interface per workflow aggregate and needs to \
                  know its type."""
                  .formatted(implementation.name())));
      final var serviceInterface = declared
          .arguments()
          .getFirst()
          .name();
      final var serviceInterfaceClass = combinedIndex
          .getIndex()
          .getClassByName(serviceInterface);
      if ((serviceInterfaceClass != null) && (serviceInterfaceClass.typeParameters().size() != 1)) {
        throw new IllegalStateException(
            """
                The service interface '%s' offered by the AggregateServiceFactory '%s' has %d type \
                parameters! It is injected as '%s<YourWorkflowAggregate>', so it has to take exactly \
                one - the workflow aggregate."""
                .formatted(
                    serviceInterface,
                    implementation.name(),
                    serviceInterfaceClass.typeParameters().size(),
                    serviceInterface.withoutPackagePrefix()));
      }
      serviceInterfaces.add(serviceInterface);
    }
    return serviceInterfaces;

  }

}
