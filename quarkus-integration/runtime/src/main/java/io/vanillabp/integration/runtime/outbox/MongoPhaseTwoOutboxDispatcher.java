package io.vanillabp.integration.runtime.outbox;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.eclipse.microprofile.config.ConfigProvider;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterPropertiesMapper;
import io.vanillabp.integration.runtime.deployment.VanillaBpDeploymentRunner;
import io.vanillabp.integration.spi.PhaseTwoCall;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * Dispatches committed-but-unprocessed entries of the MongoDB-based phase-two
 * outbox (see {@link MongoPhaseTwoOutbox}) through the core's
 * {@link PhaseTwoRouter}:
 * <ul>
 * <li>right after a commit (triggered by {@link MongoPhaseTwoOutbox}) and</li>
 * <li>by a fixed-delay poller (crash recovery and retries, poll interval configured
 * by <code>vanillabp.outbox.poll-interval</code>) started on
 * {@link StartupEvent}.</li>
 * </ul>
 * Due entries (status {@link MongoPhaseTwoOutbox#STATUS_OPEN}) are claimed
 * atomically (<code>findOneAndUpdate</code> incrementing the number of attempts and
 * leasing the entry for one <code>vanillabp.outbox.attempt-frequency</code>), so
 * multiple application instances (pods) may poll concurrently without any distributed
 * lock - exactly one instance wins each claim. A dispatch which FAILS writes the next
 * attempt itself, at the growing distance of
 * {@link io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties#attemptDelay(int)}
 * - doubling per attempt up to <code>vanillabp.outbox.max-attempt-frequency</code>. On
 * successful dispatch the entry is marked {@link MongoPhaseTwoOutbox#STATUS_DONE}
 * (kept until <code>vanillabp.outbox.retention</code> passed, for support to read);
 * after
 * <code>vanillabp.outbox.block-after-attempts</code> failed attempts it is marked
 * {@link MongoPhaseTwoOutbox#STATUS_BLOCKED} and has to be cleaned up manually.
 * <p>
 * Unless <code>vanillabp.outbox.create-schema</code> is disabled, a unique index on
 * the entries' <code>dedupKey</code> is created on startup. That field carries the
 * idempotency key only while the entry waits for its dispatch, so the index
 * deduplicates the planned operations and not the ones which already reached the BPMS
 * (see {@link MongoPhaseTwoOutbox}); marking an entry DONE writes its id there. If the
 * schema is managed manually, create that index yourself. The sparse unique index
 * earlier versions created over <code>idempotencyKey</code> is dropped where it is
 * still there - it would deduplicate dispatched entries as well.
 * <p>
 * The database is taken from <code>quarkus.mongodb.database</code> - the same
 * database the application's aggregates live in.
 */
@ApplicationScoped
@Slf4j
public class MongoPhaseTwoOutboxDispatcher {

  @Inject
  Instance<MongoClient> mongoClient;

  @Inject
  Instance<PhaseTwoRouter> phaseTwoRouter;

  /**
   * What a blocked entry is counted into. Unsatisfied where the application uses no
   * Micrometer extension, which is why it is resolved through the producer's helper
   * rather than injected directly.
   */
  @Inject
  Instance<io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics> vanillaBpMetrics;

  private volatile PhaseTwoOutboxProperties properties;

  /**
   * The outbox configuration (<code>vanillabp.outbox.*</code>), loaded lazily so
   * {@link MongoPhaseTwoOutbox} can resolve its collection even before the startup
   * event was observed.
   *
   * @return The outbox configuration
   */
  PhaseTwoOutboxProperties getProperties() {

    if (properties == null) {
      properties = QuarkusMigrationAdapterPropertiesMapper.INSTANCE.toCore(
          ConfigProvider
              .getConfig()
              .unwrap(SmallRyeConfig.class)
              .getConfigMapping(QuarkusMigrationAdapterProperties.class)
              .outbox());
    }
    return properties;

  }

  private ScheduledExecutorService executor;

  /**
   * Creates the unique index (unless disabled) and starts the fixed-delay poller.
   * The first run is executed immediately, dispatching committed-but-unprocessed
   * entries of a previously crashed instance. The observer priority guarantees that
   * the deployment pipeline deployed the BPMN resources and started workflow
   * processing BEFORE any recovered entry is dispatched (see
   * {@link VanillaBpDeploymentRunner#OUTBOX_DISPATCHER_STARTUP_PRIORITY}).
   *
   * @param event The startup event observed
   */
  void onStart(
      @Observes
      @Priority(VanillaBpDeploymentRunner.OUTBOX_DISPATCHER_STARTUP_PRIORITY) final StartupEvent event) {

    if (!mongoClient.isResolvable()) {
      log.debug("No MongoDB client available - the MongoDB-based phase-two outbox stays inactive");
      return;
    }

    getProperties();
    if (!properties.getMongo().isEnabled()) {
      log.debug("'vanillabp.outbox.mongo.enabled' is false - the MongoDB-based phase-two outbox stays inactive");
      return;
    }

    if (properties.isCreateSchema()) {
      outboxCollection().createIndex(
          Indexes.ascending("dedupKey"),
          new IndexOptions().unique(true));
      dropLegacyIdempotencyKeyIndex();
    }

    executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
      final var thread = new Thread(runnable, "vanillabp-outbox");
      thread.setDaemon(true);
      return thread;
    });
    executor.scheduleWithFixedDelay(
        this::poll,
        0,
        properties.getPollInterval().toMillis(),
        TimeUnit.MILLISECONDS);

  }

  @PreDestroy
  void shutdown() {

    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }

  }

  /**
   * Removes the sparse unique index over <code>idempotencyKey</code> which earlier
   * versions created. It deduplicated dispatched entries as well, which is what this
   * store stopped doing; an index which is not there any more is not an error.
   */
  private void dropLegacyIdempotencyKeyIndex() {

    try {
      outboxCollection().dropIndex(Indexes.ascending("idempotencyKey"));
      log.info(
          "Dropped the outbox' unique index over 'idempotencyKey': deduplication now spans the "
              + "entries still waiting for their dispatch and uses 'dedupKey'");
    } catch (final RuntimeException e) {
      // 27 = IndexNotFound, which is the normal case
      log.debug("No legacy unique index over 'idempotencyKey' to drop", e);
    }

  }

  /**
   * Runs a single poll asynchronously (used right after a commit).
   */
  public void triggerPoll() {

    if (executor != null) {
      executor.execute(this::poll);
    }

  }

  /**
   * The collection storing the outbox entries, resolved from the database
   * configured by <code>quarkus.mongodb.database</code>.
   *
   * @return The outbox collection
   */
  MongoCollection<Document> outboxCollection() {

    final var database = ConfigProvider
        .getConfig()
        .getOptionalValue("quarkus.mongodb.database", String.class)
        .orElseThrow(() -> new IllegalStateException(
            """
                The MongoDB-based phase-two outbox needs the database name! Set the property \
                'quarkus.mongodb.database' (the same database the workflow aggregates live in)."""));
    return mongoClient
        .get()
        .getDatabase(database)
        .getCollection(getProperties()
            .getMongo()
            .getCollection());

  }

  /**
   * Claims and dispatches all due entries, then deletes DONE entries whose
   * retention passed. Exceptions are caught to keep the poller alive.
   */
  private synchronized void poll() {

    try {
      final var collection = outboxCollection();
      while (true) {
        final var now = Instant.now();
        // claim atomically: increment attempts and set the backoff, so other
        // instances skip the entry and a failed dispatch is retried automatically
        final var entry = collection.findOneAndUpdate(
            Filters.and(
                Filters.eq("status", MongoPhaseTwoOutbox.STATUS_OPEN),
                Filters.lte("nextAttemptAt", Date.from(now)),
                Filters.lt("attempts", properties.getBlockAfterAttempts())),
            Updates.combine(
                Updates.inc("attempts", 1),
                Updates.set("nextAttemptAt", Date.from(now.plus(properties.getAttemptFrequency())))));
        if (entry == null) {
          break;
        }
        dispatch(collection, entry);
      }
      // asynchronous retention cleanup of the "DONE instead of delete" contract
      collection.deleteMany(
          Filters.and(
              Filters.eq("status", MongoPhaseTwoOutbox.STATUS_DONE),
              Filters.lt("doneAt", Date.from(Instant.now().minus(properties.getRetention())))));
    } catch (final RuntimeException e) {
      log.error("Polling the VanillaBP phase-two outbox failed - will retry", e);
    }

  }

  /**
   * Dispatches a single claimed entry through the core's {@link PhaseTwoRouter}. On
   * success the entry is marked DONE; on failure it stays claimed and is retried
   * after the configured backoff, until it is blocked.
   *
   * @param collection The outbox collection
   * @param entry The claimed entry (holding the state before it was claimed)
   */
  private void dispatch(
      final MongoCollection<Document> collection,
      final Document entry) {

    final var entryId = entry.getString("_id");
    try {
      final var argsDocument = entry.get("args", Document.class);
      final Map<String, String> args = new LinkedHashMap<>();
      if (argsDocument != null) {
        argsDocument.forEach((
            key,
            value) -> args.put(key, String.valueOf(value)));
      }
      // the document holds the attempts count BEFORE this claim - a value > 0
      // means the entry was dispatched before (recovered/retried): the router
      // then runs the START re-dispatch mitigation. The operation travels as its
      // persisted name and is resolved by the router's operation registry
      phaseTwoRouter
          .get()
          .dispatch(
              PhaseTwoCall
                  .forDispatch(
                      entry.getString("operation"), entry.getString("workflowModuleId"), entry
                          .getString("bpmnProcessId"),
                      entry.getString("aggregateId"), entry
                          .getString("adapterId"),
                      args),
              entry.getInteger("attempts") > 0);
      collection.updateOne(
          Filters.eq("_id", entryId),
          Updates.combine(
              Updates.set("status", MongoPhaseTwoOutbox.STATUS_DONE),
              Updates.set("doneAt", Date.from(Instant.now())),
              // the deduplication window ends with the dispatch: the entry's own id
              // takes the place of the key, which stays readable in idempotencyKey
              Updates.set("dedupKey", entryId)));
    } catch (final RuntimeException e) {
      // the adapter said that repeating cannot help - blocked right away
      // instead of after the configured attempts
      if (io.vanillabp.integration.spi.PhaseTwoPermanentFailure.isPermanent(e)) {
        collection.updateOne(
            Filters.eq("_id", entryId),
            blockEntry(entryId));
        countBlockedEntry(entry.getString("operation"), true);
        log.error(
            "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
                + "failed for a reason repeating cannot fix - the outbox entry '{}' is blocked and has "
                + "to be cleaned up manually!",
            entry.getString("operation"),
            entry.getString("bpmnProcessId"),
            entry.getString("workflowModuleId"),
            entry.getString("aggregateId"),
            entryId,
            e);
        return;
      }
      if (entry.getInteger("attempts") + 1 >= properties.getBlockAfterAttempts()) {
        collection.updateOne(
            Filters.eq("_id", entryId),
            blockEntry(entryId));
        countBlockedEntry(entry.getString("operation"), false);
        log.error(
            "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
                + "failed {} times - the outbox entry '{}' is now blocked and has to be cleaned up manually!",
            entry.getString("operation"),
            entry.getString("bpmnProcessId"),
            entry.getString("workflowModuleId"),
            entry.getString("aggregateId"),
            entry.getInteger("attempts") + 1,
            entryId,
            e);
        return;
      }
      final var retryAfter = io.vanillabp.integration.spi.PhaseTwoRetryLater.retryAfter(e);
      if (retryAfter != null) {
        // the dispatch knows when asking again can help - a workflow the BPMS has not
        // made searchable yet is the case - so the entry waits that long instead of the
        // configured backoff. What ends a reason which never goes away is the attempts
        // counted above, not this due time
        collection.updateOne(
            Filters.eq("_id", entryId),
            Updates.set("nextAttemptAt", Date.from(Instant.now().plus(retryAfter))));
        log.info(
            "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' cannot "
                + "run yet - the outbox entry '{}' is dispatched again in {} ({} of {} attempts used): {}",
            entry.getString("operation"),
            entry.getString("bpmnProcessId"),
            entry.getString("workflowModuleId"),
            entry.getString("aggregateId"),
            entryId,
            retryAfter,
            entry.getInteger("attempts") + 1,
            properties.getBlockAfterAttempts(),
            e.getMessage());
      } else {
        // the attempts of the entry are the count BEFORE this claim, so attemptDelay(0)
        // is the distance after the first failure: close, because most failures are
        // momentary
        final var retryIn = properties.attemptDelay(entry.getInteger("attempts"));
        collection.updateOne(
            Filters.eq("_id", entryId),
            Updates.set("nextAttemptAt", Date.from(Instant.now().plus(retryIn))));
        log.warn(
            "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
                + "failed - the outbox entry '{}' is dispatched again in {} ({} of {} attempts used)",
            entry.getString("operation"),
            entry.getString("bpmnProcessId"),
            entry.getString("workflowModuleId"),
            entry.getString("aggregateId"),
            entryId,
            retryIn,
            entry.getInteger("attempts") + 1,
            properties.getBlockAfterAttempts(),
            e);
      }
    }

  }

  /**
   * Counts an entry this store gave up on. The gauge of waiting entries drops at the
   * same moment, so without this counter the only number an operator watches would move
   * as if things had got better.
   *
   * @param operation The persisted name of the operation which was lost
   * @param permanent Whether the adapter said that repeating cannot help
   */
  private void countBlockedEntry(
      final String operation,
      final boolean permanent) {

    io.vanillabp.integration.runtime.processservice.PhaseTwoRouterProducer
        .vanillaBpMetricsOf(vanillaBpMetrics)
        .outboxEntryBlocked(MongoPhaseTwoOutbox.class.getSimpleName(), operation, permanent);

  }

  /**
   * Blocks an entry and releases its <code>dedupKey</code> the way a dispatched entry
   * releases it. Easy to miss and the reason a blocked entry used to be a dead end: the
   * key is what refuses a second schedule of the same operation, so a blocked entry
   * which kept it would silence the very repetition the application needs - it would
   * ask, the outbox would answer no, and that answer looks exactly like a correct
   * deduplication. The row stays for whoever repairs it, and the new attempt of the
   * operation is a document of its own.
   *
   * @param entryId The id of the entry to block
   * @return The update to apply
   */
  private static org.bson.conversions.Bson blockEntry(
      final String entryId) {

    return Updates
        .combine(
            Updates.set("status", MongoPhaseTwoOutbox.STATUS_BLOCKED),
            Updates.set("dedupKey", entryId));

  }

}
