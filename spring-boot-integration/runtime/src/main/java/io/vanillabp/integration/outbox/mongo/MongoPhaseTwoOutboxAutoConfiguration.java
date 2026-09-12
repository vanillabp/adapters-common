package io.vanillabp.integration.outbox.mongo;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.repository.MongoRepository;

import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.config.VanillaBpConfigurationProperties;
import io.vanillabp.integration.outbox.gruelbox.GruelboxPhaseTwoOutboxAutoConfiguration;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import lombok.extern.slf4j.Slf4j;

/**
 * Auto-configuration of the default {@link PhaseTwoOutbox} for MongoDB-based aggregate
 * persistence. Active whenever Spring Data MongoDB is on the classpath and a
 * {@link MongoDatabaseFactory} is available - it COEXISTS with the JPA/gruelbox
 * default ({@link GruelboxPhaseTwoOutboxAutoConfiguration}): each workflow aggregate
 * is served by the outbox matching its persistence (selection per aggregate, see
 * {@link io.vanillabp.integration.spi.PhaseTwoOutboxAware}), so outbox
 * entries always ride the aggregate's own transaction even in mixed-persistence
 * applications. Disable via <code>vanillabp.outbox.mongo.enabled</code> if the
 * default (including its collection and background dispatcher) is unwanted.
 * <p>
 * Unless <code>vanillabp.outbox.create-schema</code> is set to <code>false</code>, a
 * sparse unique index on the entries' idempotency key is created automatically - the
 * storage-level deduplication of the outbox contract. If the schema is managed
 * manually, create that index yourself (see the module's <code>README.md</code>).
 * <p>
 * <strong>Note:</strong> Transactional enlisting of outbox entries requires MongoDB
 * transactions, i.e. a replica set and a
 * <code>MongoTransactionManager</code> bean - otherwise scheduling is best-effort
 * (see {@link MongoPhaseTwoOutbox}).
 */
@AutoConfiguration(
    after = GruelboxPhaseTwoOutboxAutoConfiguration.class,
    afterName = "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration")
@ConditionalOnClass(MongoRepository.class)
@ConditionalOnBean({
    MongoDatabaseFactory.class, MongoTemplate.class
})
@ConditionalOnBooleanProperty(name = "vanillabp.outbox.mongo.enabled", matchIfMissing = true)
@EnableConfigurationProperties(VanillaBpConfigurationProperties.class)
@Slf4j
public class MongoPhaseTwoOutboxAutoConfiguration {

  /**
   * The name of the default MongoDB outbox bean - used by the resolver to attribute
   * MongoDB-persisted aggregates to THE default when several outbox beans exist.
   */
  public static final String DEFAULT_OUTBOX_BEAN_NAME = "vanillaBpMongoPhaseTwoOutbox";

  /**
   * @param mongoTemplate The template used to claim and update entries
   * @param phaseTwoRouter Provider of the core's router dispatched to
   * @param vanillaBpProperties The bound <code>vanillabp.*</code> tree carrying the
   *          <code>vanillabp.outbox</code> section (registered here as well so the
   *          outbox works in contexts without the full VanillaBP auto-configuration)
   * @param metrics Provider of what a blocked entry is counted into; Micrometer is
   *          optional, so the bean may legitimately be absent
   * @return The dispatcher polling the outbox collection (private single-thread
   *         executor - no {@link org.springframework.scheduling.TaskScheduler}
   *         involved)
   */
  @Bean
  public MongoPhaseTwoOutboxDispatcher vanillaBpMongoPhaseTwoOutboxDispatcher(
      final MongoTemplate mongoTemplate,
      final ObjectProvider<PhaseTwoRouter> phaseTwoRouter,
      final VanillaBpConfigurationProperties vanillaBpProperties,
      final ObjectProvider<io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics> metrics) {

    return new MongoPhaseTwoOutboxDispatcher(
        mongoTemplate, phaseTwoRouter, vanillaBpProperties.getOutbox(), vanillaBpProperties
            .getOutbox()
            .getMongo()
            .getCollection(), metrics);

  }

  /**
   * @param mongoTemplate The template used to write entries within the current transaction
   * @param dispatcher The dispatcher triggered right after a commit
   * @param vanillaBpProperties The bound <code>vanillabp.*</code> tree carrying the
   *          <code>vanillabp.outbox</code> section (registered here as well so the
   *          outbox works in contexts without the full VanillaBP auto-configuration)
   * @return The {@link PhaseTwoOutbox} used by the process services
   */
  @Bean(DEFAULT_OUTBOX_BEAN_NAME)
  public MongoPhaseTwoOutbox vanillaBpMongoPhaseTwoOutbox(
      final MongoTemplate mongoTemplate,
      final MongoPhaseTwoOutboxDispatcher dispatcher,
      final VanillaBpConfigurationProperties vanillaBpProperties) {

    final var collection = vanillaBpProperties
        .getOutbox()
        .getMongo()
        .getCollection();
    if (vanillaBpProperties.getOutbox().isCreateSchema()) {
      // 'dedupKey' and not 'idempotencyKey': the key deduplicates the operations still
      // waiting for their dispatch, and the field holds the entry's own id once it was
      // dispatched (see MongoPhaseTwoOutbox). Present on every entry, so the index
      // needs neither sparse nor a partial filter
      mongoTemplate
          .indexOps(collection)
          .createIndex(new Index()
              .on("dedupKey", Sort.Direction.ASC)
              .unique());
      // what the dispatcher asks on every wake-up, and two indexes rather than one: both questions
      // filter the same status and order by a different moment, so an index over both moments would
      // serve neither. Without them each question reads the whole collection, which costs more the
      // longer the application has been running
      mongoTemplate
          .indexOps(collection)
          .createIndex(new Index()
              .on("status", Sort.Direction.ASC)
              .on("nextAttemptAt", Sort.Direction.ASC));
      mongoTemplate
          .indexOps(collection)
          .createIndex(new Index()
              .on("status", Sort.Direction.ASC)
              .on("doneAt", Sort.Direction.ASC));
      dropLegacyIdempotencyKeyIndex(mongoTemplate, collection);
    }
    return new MongoPhaseTwoOutbox(mongoTemplate, dispatcher, collection);

  }

  /**
   * Removes the sparse unique index over <code>idempotencyKey</code> which earlier
   * versions created. It deduplicated dispatched entries as well, which is what this
   * store stopped doing; an index which is not there any more is not an error.
   */
  private static void dropLegacyIdempotencyKeyIndex(
      final MongoTemplate mongoTemplate,
      final String collection) {

    try {
      mongoTemplate
          .indexOps(collection)
          .dropIndex("idempotencyKey");
    } catch (final RuntimeException e) {
      // not there is the normal case
      log.debug("No legacy unique index over 'idempotencyKey' to drop", e);
    }

  }

}
