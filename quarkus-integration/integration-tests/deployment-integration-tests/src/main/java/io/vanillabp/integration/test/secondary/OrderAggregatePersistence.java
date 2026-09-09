package io.vanillabp.integration.test.secondary;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory persistence for the called-process test's aggregate, copying on save and load
 * so an un-saved mutation never leaks into the store.
 */
@ApplicationScoped
public class OrderAggregatePersistence implements AggregatePersistenceAware<OrderAggregate> {

  private final Map<String, OrderAggregate> aggregates = new ConcurrentHashMap<>();

  @Override
  public Class<OrderAggregate> getAggregateClass() {

    return OrderAggregate.class;

  }

  @Override
  public OrderAggregate save(
      final OrderAggregate aggregate) {

    aggregates.put(aggregate.getId(), copyOf(aggregate));
    return aggregate;

  }

  @Override
  public Object getAggregateId(
      final OrderAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public Class<?> getAggregateIdType() {

    return String.class;

  }

  @Override
  public OrderAggregate loadById(
      final Object aggregateId) {

    final var stored = aggregates.get(aggregateId);
    return stored != null
        ? copyOf(stored)
        : null;

  }

  /**
   * Stores an aggregate the test starts from, without going through a workflow start.
   *
   * @param id The aggregate's ID
   */
  public void store(
      final String id) {

    final var aggregate = new OrderAggregate();
    aggregate.setId(id);
    aggregate.setStatus("new");
    aggregates.put(id, aggregate);

  }

  /**
   * @param id The aggregate's ID
   * @return The stored aggregate
   */
  public OrderAggregate get(
      final String id) {

    return copyOf(aggregates.get(id));

  }

  private static OrderAggregate copyOf(
      final OrderAggregate aggregate) {

    final var copy = new OrderAggregate();
    copy.setId(aggregate.getId());
    copy.setStatus(aggregate.getStatus());
    return copy;

  }

}
