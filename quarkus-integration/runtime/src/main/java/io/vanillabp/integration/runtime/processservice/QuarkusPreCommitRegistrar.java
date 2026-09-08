package io.vanillabp.integration.runtime.processservice;

import io.vanillabp.integration.adapter.migration.processservice.TransactionRunnerResolver;
import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Quarkus implementation of the adapter-facing {@link PreCommitRegistrar}.
 * <p>
 * It resolves the transaction runner of the workflow aggregate first, so a phase-one check
 * hooks into the unit of work VanillaBP actually uses - which may be one the
 * APPLICATION contributed. The resolution is the same one the process services use, the
 * bean of {@link TransactionRunnerProducer} rather than a resolver of its own, and it
 * caches per aggregate class.
 */
@ApplicationScoped
public class QuarkusPreCommitRegistrar implements PreCommitRegistrar {

  @Inject
  TransactionRunnerResolver transactionRunnerResolver;

  @Override
  public void beforeCommit(
      final Class<?> workflowAggregateClass,
      final Runnable check) {

    transactionRunnerResolver
        .resolveFor(workflowAggregateClass)
        .beforeCommit(check);

  }

}
