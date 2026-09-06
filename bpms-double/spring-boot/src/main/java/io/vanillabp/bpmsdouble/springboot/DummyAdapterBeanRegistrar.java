package io.vanillabp.bpmsdouble.springboot;

import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.core.env.Environment;

import io.vanillabp.bpmsdouble.DummyAdapter;
import io.vanillabp.bpmsdouble.DummyBpmsInitiatedStartSource;
import io.vanillabp.bpmsdouble.DummyDeploymentListener;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.bpmsdouble.DummyHealthSource;
import io.vanillabp.bpmsdouble.DummyPhaseTwoListener;
import io.vanillabp.bpmsdouble.DummyProcessService;
import io.vanillabp.bpmsdouble.DummyProcessVersionSource;
import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.bpmsdouble.DummyViewerSource;
import io.vanillabp.integration.adapter.AdapterBeanRegistrarSupport;

/**
 * Registers the double's per-adapter-id beans - the reference implementation of the
 * per-id bean convention every VanillaBP adapter follows on Spring Boot: for EACH
 * configured adapter id of the adapter's type (multiple ids of one BPMS type = the
 * migration scenario) one {@code MigratableProcessService} <i>element</i> bean and one
 * {@code AdapterDeploymentService} <i>element</i> bean are registered - never beans of
 * type {@code List<...>}: the platform collects element beans via
 * {@code ObjectProvider.stream()}.
 * <p>
 * The id set comes from the runtime configuration, so the beans are registered
 * programmatically ({@link BeanRegistrar} +
 * {@link AdapterBeanRegistrarSupport#forEachConfiguredAdapterId}); the adapter id is a
 * CONSTRUCTOR parameter of each instance. The bean suppliers are lazy: other beans are
 * resolved through the {@code SupplierContext} at bean-creation time.
 * <p>
 * The names of the two beans are part of what this artifact promises, because tests
 * pull the deployment service out of the container by its name to play the BPMS. See
 * the readme of the <code>bpms-double</code> module.
 */
public class DummyAdapterBeanRegistrar implements BeanRegistrar {

  @Override
  public void register(
      final BeanRegistry registry,
      final Environment environment) {

    final var deliversTasksAtLeastOnce = Boolean.TRUE.equals(
        environment.getProperty(DummyAdapter.PROPERTY_AT_LEAST_ONCE_DELIVERY, Boolean.class, Boolean.FALSE));
    final var readsAggregateInPhaseTwo = Boolean.TRUE.equals(
        environment.getProperty(DummyAdapter.PROPERTY_READ_AGGREGATE_IN_PHASE_TWO, Boolean.class, Boolean.FALSE));

    AdapterBeanRegistrarSupport.forEachConfiguredAdapterId(
        environment,
        DummyAdapter.ADAPTER_TYPE,
        adapterId -> {

          registry.registerBean(
              "DummyAdapter_ProcessService_%s".formatted(adapterId),
              DummyProcessService.class,
              spec -> spec.supplier(supplierContext -> new DummyProcessService<>(
                  adapterId, deliversTasksAtLeastOnce, readsAggregateInPhaseTwo, supplierContext
                      .beanProvider(DummyPhaseTwoListener.class)::stream, supplierContext
                          .beanProvider(DummyTaskAwarenessSource.class)::stream, supplierContext
                              .beanProvider(DummyViewerSource.class)::stream)));

          registry.registerBean(
              "DummyAdapter_DeploymentService_%s".formatted(adapterId),
              DummyDeploymentService.class,
              spec -> spec.supplier(supplierContext -> new DummyDeploymentService(
                  adapterId, supplierContext
                      .beanProvider(DummyDeploymentListener.class)::stream, AdapterBeanRegistrarSupport.collaborators(
                          supplierContext,
                          adapterId), supplierContext.beanProvider(DummyTaskWiringSource.class)::stream, supplierContext
                              .beanProvider(DummyBpmsInitiatedStartSource.class)::stream, supplierContext
                                  .beanProvider(DummyProcessVersionSource.class)::stream, supplierContext
                                      .beanProvider(DummyHealthSource.class)::stream)));

        });

  }

}
