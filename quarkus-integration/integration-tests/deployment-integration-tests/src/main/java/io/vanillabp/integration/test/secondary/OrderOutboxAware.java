package io.vanillabp.integration.test.secondary;

import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.PhaseTwoOutboxAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Attributes the test's aggregate to the outbox which never dispatches, so the JDBC outbox
 * the data source brings along stays out of this test. The delivery log keeps using that
 * data source, which is where the records this test reads really live.
 */
@ApplicationScoped
public class OrderOutboxAware implements PhaseTwoOutboxAware<OrderAggregate> {

  @Inject
  OrderOutbox orderOutbox;

  @Override
  public Class<OrderAggregate> getAggregateClass() {

    return OrderAggregate.class;

  }

  @Override
  public PhaseTwoOutbox getPhaseTwoOutbox() {

    return orderOutbox;

  }

}
