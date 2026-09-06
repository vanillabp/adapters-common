package io.vanillabp.integration.test.samples.ambiguoussubclasses;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.inject.Singleton;

/**
 * Nothing of this test reaches a workflow aggregate, so the persistence answers what it
 * has to answer and nothing else.
 */
@Singleton
public class AmbiguousAggregatePersistence implements AggregatePersistenceAware<AmbiguousAggregate> {

  @Override
  public Class<AmbiguousAggregate> getAggregateClass() {
    return AmbiguousAggregate.class;
  }

  @Override
  public AmbiguousAggregate save(
      final AmbiguousAggregate aggregate) {
    return aggregate;
  }

  @Override
  public Object getAggregateId(
      final AmbiguousAggregate aggregate) {
    return null;
  }

}
