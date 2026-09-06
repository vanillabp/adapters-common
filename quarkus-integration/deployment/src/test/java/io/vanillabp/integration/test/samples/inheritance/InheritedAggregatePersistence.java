package io.vanillabp.integration.test.samples.inheritance;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.inject.Singleton;

/**
 * Nothing of this test reaches a workflow aggregate, so the persistence answers what it
 * has to answer and nothing else.
 */
@Singleton
public class InheritedAggregatePersistence implements AggregatePersistenceAware<InheritedAggregate> {

  @Override
  public Class<InheritedAggregate> getAggregateClass() {
    return InheritedAggregate.class;
  }

  @Override
  public InheritedAggregate save(
      final InheritedAggregate aggregate) {
    return aggregate;
  }

  @Override
  public Object getAggregateId(
      final InheritedAggregate aggregate) {
    return null;
  }

}
