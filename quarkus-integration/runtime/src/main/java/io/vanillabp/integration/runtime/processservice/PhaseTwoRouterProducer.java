package io.vanillabp.integration.runtime.processservice;

import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.runtime.workflowtask.QuarkusTransactionRunner;
import io.vanillabp.integration.spi.PhaseOperationRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces the core-owned {@link PhaseTwoRouter} as CDI bean: the generated
 * process-service beans (see {@link ProcessServiceBaseCdiBean}) register themselves
 * with the router at bean creation, and the phase-two outbox dispatcher routes
 * committed outbox entries through it.
 * <p>
 * The router gets a {@link QuarkusTransactionRunner}: dispatching happens on the
 * outbox dispatcher's own thread and calls the application's aggregate persistence,
 * which needs a transaction and an active CDI request context - neither of which
 * exists on that thread. Providing it here covers VanillaBP's two outboxes and any
 * outbox an application contributes.
 */
@ApplicationScoped
public class PhaseTwoRouterProducer {

  /**
   * @param platformTransactionRunner The transaction a dispatch runs in - the
   *          platform's own runner, the bean of {@link TransactionRunnerProducer}
   * @param metrics What dispatches are counted into; unsatisfied where the
   *          application uses no Micrometer extension
   * @return The router
   */
  @Produces
  @Singleton
  public PhaseTwoRouter phaseTwoRouter(
      final QuarkusTransactionRunner platformTransactionRunner,
      final jakarta.enterprise.inject.Instance<io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics> metrics) {

    final var router = new PhaseTwoRouter(platformTransactionRunner);
    router.setMetrics(vanillaBpMetricsOf(metrics));
    return router;

  }

  /**
   * The metrics implementation to use: the Micrometer one where the application uses
   * the Micrometer extension,
   * {@link io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics#NONE}
   * otherwise.
   *
   * @param metrics The injected metrics
   * @return What to record into, never <code>null</code>
   */
  public static io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics vanillaBpMetricsOf(
      final jakarta.enterprise.inject.Instance<io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics> metrics) {

    return ((metrics != null) && metrics.isResolvable())
        ? metrics.get()
        : io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.NONE;

  }

  /**
   * The router's registry of phase-two operations - injectable so an extension can
   * register operations of its own (VanillaBP's core operations are registered by
   * the router itself).
   *
   * @param phaseTwoRouter The router owning the registry
   * @return The operation registry
   */
  @Produces
  @Singleton
  public PhaseOperationRegistry phaseTwoOperationRegistry(
      final PhaseTwoRouter phaseTwoRouter) {

    return phaseTwoRouter.getOperations();

  }

  /**
   * The election, offered to extensions: which BPMS holds a given workflow right now.
   * An extension addressing the first-priority adapter instead would talk to the wrong
   * BPMS for every workflow a migration has already moved.
   *
   * @param phaseTwoRouter The router holding the process services of this application
   * @return The extension-facing election
   */
  @Produces
  @Singleton
  @io.quarkus.arc.Unremovable
  public io.vanillabp.integration.extension.spi.election.WorkflowElection workflowElection(
      final PhaseTwoRouter phaseTwoRouter) {

    return new io.vanillabp.integration.adapter.migration.processservice.ExtensionWorkflowElection(phaseTwoRouter);

  }

}
