package io.vanillabp.integration.spi;

/**
 * Implemented by classes which are aware of persisting aggregates of the given type.
 * <p>
 * This interface may be implemented by the platform-specific adapter to provide generic aggregate persistence.
 * However, if the platform does not support this or an aggregate has to be persisted differently, then
 * the business processing application may provide additional implementations (e.g., by the service annotated
 * by the @{@link io.vanillabp.spi.service.WorkflowService} annotation). The implementation with the most specific
 * generic parameter is chosen, also taking superclasses and implemented interfaces into account.
 * <p>
 * This is the single, platform-independent interface: business code implements it regardless
 * of whether the application runs on Spring Boot or Quarkus.
 * <p>
 * <b>What VanillaBP guarantees around the calls.</b> Loading the aggregate, invoking the
 * <code>&#64;WorkflowTask</code> method, saving the aggregate, remembering the delivery and
 * scheduling a phase-two outbox entry happen inside ONE transaction, which VanillaBP opens
 * on the threads it owns (an adapter thread delivering a task, the outbox dispatcher's
 * thread) - so no implementation needs a transaction annotation for VanillaBP's sake.
 * <p>
 * Which transaction that is depends on the store: the platform's own where the platform
 * manages the persistence (JPA/JDBC, and MongoDB Panache on Quarkus, which binds a MongoDB
 * transaction to the JTA one), and the application's own where it does not. An
 * implementation writing into a system the platform does not manage - an event store, a
 * ledger, a message producer, a service behind an API - therefore comes with a
 * {@link TransactionRunner}, contributed as a bean or attributed per aggregate through
 * {@link TransactionRunnerAware}. Without one, VanillaBP cannot bracket the three calls it
 * makes, and it says so at startup where it can tell (see the wiki page "Workflow
 * aggregates" for what the guarantees shrink to).
 * <p>
 * All methods are <code>default</code> methods throwing an
 * {@link UnsupportedOperationException} with a guiding message: this keeps
 * hand-written implementations source-compatible when methods are added to this
 * interface. The platform-provided implementations (e.g. based on Spring Data)
 * override all of them; custom implementations have to override every method
 * VanillaBP actually uses for their aggregates.
 *
 * @param <A> The aggregate type
 */
public interface AggregatePersistenceAware<A> {

  /**
   * @return The aggregate class.
   */
  default Class<A> getAggregateClass() {

    throw new UnsupportedOperationException(
        """
            getAggregateClass is not implemented by '%s'! VanillaBP uses it to select the \
            implementation responsible for a workflow aggregate - implement getAggregateClass in \
            your AggregatePersistenceAware implementation."""
            .formatted(getClass().getName()));

  }

  /**
   * Persists the given aggregate.
   *
   * @param aggregate The aggregate to persist
   * @return The persisted aggregate, in case of ORM frameworks an attached object is returned.
   */
  default A save(
      final A aggregate) {

    throw new UnsupportedOperationException(
        """
            save is not implemented by '%s'! VanillaBP needs to persist the workflow aggregate \
            (e.g. when starting a workflow or after processing a BPMN task) - implement save in \
            your AggregatePersistenceAware implementation."""
            .formatted(getClass().getName()));

  }

  /**
   * @param aggregate The aggregate to investigate
   * @return The aggregate's ID.
   */
  default Object getAggregateId(
      final A aggregate) {

    throw new UnsupportedOperationException(
        """
            getAggregateId is not implemented by '%s'! VanillaBP needs the workflow aggregate's ID \
            (e.g. as the workflow's business key) - implement getAggregateId in your \
            AggregatePersistenceAware implementation."""
            .formatted(getClass().getName()));

  }

  /**
   * Determines the name of the aggregate's ID property. How the aggregate's ID is
   * stored in the BPMS is the adapter's decision: adapters having a dedicated
   * business-identifier concept (e.g. the Camunda 7 business key) do not need this
   * name, whereas adapters storing the aggregate (or its ID) in the BPMS itself
   * (e.g. Camunda 8) name the process variable after the aggregate's ID property.
   * <p>
   * The platform-provided implementations (e.g. based on Spring Data) support this
   * out of the box; custom implementations have to override this method if such an
   * adapter is used.
   *
   * @return The name of the aggregate's ID property
   */
  default String getAggregateIdName() {

    throw new UnsupportedOperationException(
        """
            getAggregateIdName is not implemented by '%s'! The configured VanillaBP adapter stores \
            the workflow aggregate's ID in the BPMS named like the aggregate's ID property - \
            implement getAggregateIdName in your AggregatePersistenceAware implementation."""
            .formatted(getClass().getName()));

  }

  /**
   * Determines the type of the aggregate's ID property. VanillaBP uses it at startup
   * to validate that the ID round-trips losslessly through the phase-two outbox's
   * String serialization, and at outbox dispatch to convert the serialized ID back
   * to this type.
   * <p>
   * <b>This default does NOT throw</b> (unlike the other defaults): a
   * <code>null</code> return means the ID type is not determinable - the custom
   * persistence layer then owns the serialized form (the ID is passed through as a
   * String and nothing is validated). The default determines the type by reflection
   * (see {@link AggregateIdTypes}); persistence-framework-backed implementations
   * (e.g. based on Spring Data) override it with the framework's authoritative
   * answer. Override it to return <code>null</code> explicitly if your persistence
   * layer handles the serialized form itself.
   *
   * @return The type of the aggregate's ID property or <code>null</code> if not
   *         determinable
   */
  default Class<?> getAggregateIdType() {

    return AggregateIdTypes
        .determineIdType(getAggregateClass())
        .orElse(null);

  }

  /**
   * Whether this persistence layer notices that somebody else changed the aggregate in
   * between, instead of writing over it without a word.
   * <p>
   * VanillaBP asks while the application starts, to warn about an aggregate which a
   * handler is allowed to save and which nothing can protect. A second writer is not
   * exotic: the application writes from its own endpoint, a BPMN model with two tokens
   * writes from two branches, an extension writes while it reports. Where the answer is
   * <code>false</code> the later write wins and nobody learns of the earlier one.
   * <p>
   * <b>This default does NOT throw</b> (unlike most of the others): it looks for the
   * attribute a persistence layer increments per write, by reflection and by the name every
   * supported layer gives the annotation (see {@link VersionAttribute}). A store which
   * notices a second writer some other way - a ledger which appends, an event store, a
   * document store with its own condition - overrides this and says so, and then the
   * warning stays away.
   *
   * @return Whether a concurrent change of this aggregate is noticed rather than overwritten
   */
  default boolean detectsConcurrentModification() {

    return VersionAttribute.isDeclaredBy(getAggregateClass());

  }

  /**
   * Loads the aggregate by its ID. Used by VanillaBP e.g. when processing BPMN
   * tasks (the aggregate is loaded, the business method is executed and the
   * aggregate is saved within one transaction).
   * <p>
   * The platform-provided implementations (e.g. based on Spring Data) support this
   * out of the box; custom implementations have to override this method.
   *
   * @param aggregateId The aggregate's ID (as returned by {@link #getAggregateId(Object)})
   * @return The aggregate or <code>null</code> if there is none having the given ID
   */
  default A loadById(
      final Object aggregateId) {

    throw new UnsupportedOperationException(
        """
            loadById is not implemented by '%s'! VanillaBP needs to load the workflow aggregate by \
            its ID (e.g. when processing BPMN tasks) - implement loadById in your \
            AggregatePersistenceAware implementation."""
            .formatted(getClass().getName()));

  }

}
