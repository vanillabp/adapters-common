package io.vanillabp.bpmsdouble.quarkus;

import java.util.List;

import org.eclipse.microprofile.config.ConfigProvider;

import io.vanillabp.bpmsdouble.DummyAdapter;
import io.vanillabp.bpmsdouble.DummyPhaseTwoListener;
import io.vanillabp.bpmsdouble.DummyProcessService;
import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.bpmsdouble.DummyViewerSource;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;

/**
 * Provides the double's {@link DummyProcessService} instances - the
 * reference implementation of the per-adapter-id bean convention every VanillaBP
 * adapter follows on Quarkus: a CDI producer cannot yield N element beans for N
 * runtime-configured adapter ids, so the adapter produces ONE bean of type
 * <code>List&lt;MigratableProcessService&gt;</code> with one instance PER configured
 * adapter id of its type (multiple ids of one BPMS type = the migration scenario);
 * the platform's collection point flattens List beans alongside element beans. The
 * adapter id is a CONSTRUCTOR parameter of each instance.
 * <p>
 * The adapter-id set ALWAYS comes from the platform's core properties
 * ({@code adapterIdsOfType()}, which is where the whole rule lives - filtering
 * {@code adapterTypes()} would lose the ids an application names in
 * <code>prioritized-adapters</code> without a section, and the id an application
 * configuring nothing gets from the classpath). Adapter-owned overlay maps of the
 * <code>vanillabp.*</code> tree are per-known-id lookups only and must never be
 * iterated to discover ids.
 * <p>
 * Optional {@link DummyPhaseTwoListener} beans are notified on phase two, and the
 * properties of {@link DummyAdapter} switch on the two behaviours a real BPMS has and
 * a logging double otherwise would not.
 */
@ApplicationScoped
public class DummyProcessServiceProducer {

  /**
   * See {@link DummyAdapter#ADAPTER_TYPE}.
   */
  public static final String ADAPTER_TYPE = DummyAdapter.ADAPTER_TYPE;

  /**
   * See {@link DummyAdapter#PROPERTY_AT_LEAST_ONCE_DELIVERY}.
   */
  public static final String PROPERTY_AT_LEAST_ONCE_DELIVERY = DummyAdapter.PROPERTY_AT_LEAST_ONCE_DELIVERY;

  /**
   * See {@link DummyAdapter#PROPERTY_READ_AGGREGATE_IN_PHASE_TWO}.
   */
  public static final String PROPERTY_READ_AGGREGATE_IN_PHASE_TWO = DummyAdapter.PROPERTY_READ_AGGREGATE_IN_PHASE_TWO;

  @Produces
  public List<io.vanillabp.integration.adapter.spi.MigratableProcessService<Object>> dummyMigratableProcessServices(
      final MigrationAdapterProperties properties,
      @Any final Instance<DummyPhaseTwoListener> phaseTwoListeners,
      @Any final Instance<DummyTaskAwarenessSource> taskAwarenessSources,
      @Any final Instance<DummyViewerSource> viewerSources) {

    final var deliversTasksAtLeastOnce = ConfigProvider
        .getConfig()
        .getOptionalValue(PROPERTY_AT_LEAST_ONCE_DELIVERY, Boolean.class)
        .orElse(Boolean.FALSE);
    final var readsAggregateInPhaseTwo = ConfigProvider
        .getConfig()
        .getOptionalValue(PROPERTY_READ_AGGREGATE_IN_PHASE_TWO, Boolean.class)
        .orElse(Boolean.FALSE);

    return properties
        .adapterIdsOfType(ADAPTER_TYPE)
        .stream().<io.vanillabp.integration.adapter.spi.MigratableProcessService<Object>>map(
            adapterId -> new DummyProcessService<>(
                adapterId, deliversTasksAtLeastOnce, readsAggregateInPhaseTwo, phaseTwoListeners::stream, taskAwarenessSources::stream, viewerSources::stream))
        .toList();

  }

}
