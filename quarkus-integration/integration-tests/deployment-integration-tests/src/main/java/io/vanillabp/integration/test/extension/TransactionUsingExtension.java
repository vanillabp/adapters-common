package io.vanillabp.integration.test.extension;

import io.vanillabp.integration.adapter.migration.processservice.TransactionRunnerResolver;
import io.vanillabp.integration.spi.TransactionRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * An extension in miniature: something which writes next to a workflow aggregate and
 * therefore has to run in the transaction of that aggregate rather than in one of its
 * own. It injects what the platform offers - the resolver answering per aggregate, and
 * the platform's own runner - like any other bean of the application. The point of this
 * class is that both injections resolve at all, and that an extension needs nothing of
 * the platform's internals to reach them.
 */
@ApplicationScoped
public class TransactionUsingExtension {

  @Inject
  TransactionRunnerResolver transactionRunnerResolver;

  @Inject
  TransactionRunner transactionRunner;

  /**
   * @param workflowAggregateClass The aggregate whose transaction the write belongs into
   * @return The runner of that aggregate
   */
  public TransactionRunner runnerOf(
      final Class<?> workflowAggregateClass) {

    return transactionRunnerResolver.resolveFor(workflowAggregateClass);

  }

  /**
   * @return How the platform describes the transaction it resolved for that aggregate
   */
  public String describeResolutionFor(
      final Class<?> workflowAggregateClass) {

    return transactionRunnerResolver.describeResolutionFor(workflowAggregateClass);

  }

  /**
   * @return The platform's own runner, injected as the SPI type
   */
  public TransactionRunner platformRunner() {

    return transactionRunner;

  }

}
