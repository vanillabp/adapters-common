package io.vanillabp.integration.test.samples.sharedprocess;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.inject.Singleton;

/**
 * Nothing of this test reaches a workflow aggregate, so the persistence answers what it
 * has to answer and nothing else.
 */
@Singleton
public class SharedAggregatePersistence implements AggregatePersistenceAware<SharedAggregate> {

  @Override
  public Class<SharedAggregate> getAggregateClass() {
    return SharedAggregate.class;
  }

  @Override
  public SharedAggregate save(
      final SharedAggregate aggregate) {
    return aggregate;
  }

  @Override
  public Object getAggregateId(
      final SharedAggregate aggregate) {
    return null;
  }

}
