package io.vanillabp.integration.test.extension;

import io.vanillabp.integration.adapter.migration.processservice.TransactionRunnerResolver;
import io.vanillabp.integration.spi.TransactionRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * An extension in miniature: something which writes next to a workflow aggregate and
 * therefore has to run in the transaction of that aggregate rather than in one of its
 * own. It injects the RESOLVER and asks it per aggregate class, which is the whole
 * contract - the platform's own runner is what the answer is where the application
 * contributed none, and injecting a runner directly would be ambiguous the moment an
 * application brings one.
 */
@ApplicationScoped
public class TransactionUsingExtension {

  @Inject
  TransactionRunnerResolver transactionRunnerResolver;

  /**
   * @param workflowAggregateClass The aggregate whose transaction the write belongs into
   * @return The runner of that aggregate
   */
  public TransactionRunner runnerOf(
      final Class<?> workflowAggregateClass) {

    return transactionRunnerResolver.resolveFor(workflowAggregateClass);

  }

  /**
   * @param workflowAggregateClass The aggregate
   * @return How the platform describes the transaction it resolved for that aggregate
   */
  public String describeResolutionFor(
      final Class<?> workflowAggregateClass) {

    return transactionRunnerResolver.describeResolutionFor(workflowAggregateClass);

  }

}
