package io.vanillabp.integration.runtime.processservice;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.arc.Unremovable;
import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.PhaseTwoOutboxAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Produces the {@link PhaseTwoOutboxResolver} of this platform as a CDI bean, the one
 * place a Quarkus application builds it: the generated process-service beans (see
 * {@link ProcessServiceBaseCdiBean}) resolve the outbox of their aggregate through it,
 * and an extension writing entries of its own into the aggregate's transaction asks it
 * which store that transaction reaches.
 * <p>
 * An extension must not answer that question itself. Whether a store may serve an
 * aggregate depends on the persistence VanillaBP resolved for the aggregate and on
 * whether a platform default is switched on and usable at all - facts of this module
 * ({@link PlatformDefaultStore} is not part of any SPI), so an extension guessing along
 * would write entries next to the aggregate instead of into its transaction. The Spring
 * Boot integration offers its resolver as a bean for the same reason.
 * <p>
 * The configuration is READ, not injected, for the reason
 * {@link WorkflowAdapterCacheProducer} names: an injected config mapping is validated
 * before the adapter extensions registered their run-time overlays, and every
 * adapter-specific key would then end the startup as unknown.
 */
@ApplicationScoped
public class PhaseTwoOutboxResolverProducer {

  /**
   * Application-provided attributions of aggregates to outboxes (required where the
   * application brought the persistence itself, optional otherwise).
   */
  @Inject
  @Any
  Instance<PhaseTwoOutboxAware<?>> phaseTwoOutboxAwares;

  /**
   * The outboxes available at runtime: the platform defaults of both technologies plus
   * whatever the application contributed.
   */
  @Inject
  @Any
  Instance<PhaseTwoOutbox> phaseTwoOutboxes;

  /**
   * The persistences of the application, which is what an aggregate's store is read off.
   * Injected as {@link Instance} because a class annotated by
   * {@link io.vanillabp.spi.service.WorkflowService} may implement
   * {@link AggregatePersistenceAware} itself.
   */
  @Inject
  @Any
  Instance<AggregatePersistenceAware<?>> aggregatePersistences;

  /**
   * @return The resolver, injectable by extensions and used by the process services
   */
  @Produces
  @Singleton
  @Unremovable
  public PhaseTwoOutboxResolver phaseTwoOutboxResolver() {

    final var outboxProperties = ConfigProvider
        .getConfig()
        .unwrap(SmallRyeConfig.class)
        .getConfigMapping(QuarkusMigrationAdapterProperties.class)
        .outbox();
    return new QuarkusPhaseTwoOutboxResolver(
        phaseTwoOutboxAwares, phaseTwoOutboxes, new QuarkusPersistenceTechnology(aggregatePersistences), outboxProperties
            .jdbc()
            .enabled(), outboxProperties
                .mongo()
                .enabled());

  }

}
