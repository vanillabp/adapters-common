package io.vanillabp.integration.test.mixed;

import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * An extension in miniature: something which writes entries of its own into the
 * transaction of a workflow aggregate and therefore has to ask the platform which store
 * that transaction reaches. It injects the resolver like any other bean of the
 * application - the point of this class is that the injection resolves at all, and that
 * an extension needs nothing of the platform's internals to get the answer.
 */
@ApplicationScoped
public class OutboxUsingExtension {

  @Inject
  PhaseTwoOutboxResolver phaseTwoOutboxResolver;

  /**
   * @param workflowAggregateClass The aggregate whose transaction the entry belongs into
   * @return The outbox to write into
   */
  public PhaseTwoOutbox outboxOf(
      final Class<?> workflowAggregateClass) {

    return phaseTwoOutboxResolver.resolveFor(workflowAggregateClass);

  }

}
