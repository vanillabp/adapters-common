package io.vanillabp.integration.test.inheritance;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory persistence for the inheritance acceptance test's aggregate.
 */
@ApplicationScoped
public class InheritedAggregatePersistence implements AggregatePersistenceAware<InheritedAggregate> {

  private final Map<String, InheritedAggregate> aggregates = new ConcurrentHashMap<>();

  @Override
  public Class<InheritedAggregate> getAggregateClass() {

    return InheritedAggregate.class;

  }

  @Override
  public InheritedAggregate save(
      final InheritedAggregate aggregate) {

    aggregates.put(aggregate.getId(), copyOf(aggregate));
    return aggregate;

  }

  @Override
  public Object getAggregateId(
      final InheritedAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public Class<?> getAggregateIdType() {

    return String.class;

  }

  @Override
  public InheritedAggregate loadById(
      final Object aggregateId) {

    final var stored = aggregates.get(aggregateId);
    return stored != null
        ? copyOf(stored)
        : null;

  }

  /**
   * Direct store access for test assertions (only saved state is visible).
   *
   * @param id The aggregate's ID
   * @return The stored aggregate or <code>null</code>
   */
  public InheritedAggregate stored(
      final String id) {

    return aggregates.get(id);

  }

  /**
   * Seeds an aggregate into the store, as if a workflow had been started.
   *
   * @param id The aggregate's ID
   */
  public void seed(
      final String id) {

    final var aggregate = new InheritedAggregate();
    aggregate.setId(id);
    aggregate.setServedBy("nobody");
    aggregates.put(id, aggregate);

  }

  private static InheritedAggregate copyOf(
      final InheritedAggregate aggregate) {

    final var copy = new InheritedAggregate();
    copy.setId(aggregate.getId());
    copy.setServedBy(aggregate.getServedBy());
    return copy;

  }

}
