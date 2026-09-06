package io.vanillabp.integration.test.inheritance;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Nothing of that test reaches an aggregate: the point is what the boot says about the two
 * handler methods, not what a workflow does.
 */
@ApplicationScoped
public class InvisibleHandlersAggregatePersistence implements AggregatePersistenceAware<InvisibleHandlersAggregate> {

  @Override
  public Class<InvisibleHandlersAggregate> getAggregateClass() {

    return InvisibleHandlersAggregate.class;

  }

  @Override
  public InvisibleHandlersAggregate save(
      final InvisibleHandlersAggregate aggregate) {

    return aggregate;

  }

  @Override
  public Object getAggregateId(
      final InvisibleHandlersAggregate aggregate) {

    return aggregate.getId();

  }

}
