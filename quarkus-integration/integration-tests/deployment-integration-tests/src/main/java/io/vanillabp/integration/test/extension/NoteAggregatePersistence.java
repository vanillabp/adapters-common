package io.vanillabp.integration.test.extension;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory persistence for the aggregate of the extension acceptance test. Aggregates
 * are copied on save and load, so what a handler changed without being saved never leaks
 * into the store - which is what makes the save observable at all.
 */
@ApplicationScoped
public class NoteAggregatePersistence implements AggregatePersistenceAware<NoteAggregate> {

  private final Map<String, NoteAggregate> aggregates = new ConcurrentHashMap<>();

  @Override
  public Class<NoteAggregate> getAggregateClass() {

    return NoteAggregate.class;

  }

  @Override
  public String getAggregateIdName() {

    return "id";

  }

  @Override
  public Class<?> getAggregateIdType() {

    return String.class;

  }

  @Override
  public Object getAggregateId(
      final NoteAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public NoteAggregate save(
      final NoteAggregate aggregate) {

    aggregates.put(aggregate.getId(), copyOf(aggregate));
    return aggregate;

  }

  @Override
  public NoteAggregate loadById(
      final Object aggregateId) {

    final var stored = aggregates.get(aggregateId);
    return stored != null
        ? copyOf(stored)
        : null;

  }

  /**
   * @param aggregateId The aggregate's ID
   * @return What is stored under that ID, or <code>null</code>
   */
  public NoteAggregate stored(
      final String aggregateId) {

    return aggregates.get(aggregateId);

  }

  private static NoteAggregate copyOf(
      final NoteAggregate aggregate) {

    final var copy = new NoteAggregate();
    copy.setId(aggregate.getId());
    copy.setContent(aggregate.getContent());
    copy.setTouched(aggregate.getTouched());
    return copy;

  }

}
