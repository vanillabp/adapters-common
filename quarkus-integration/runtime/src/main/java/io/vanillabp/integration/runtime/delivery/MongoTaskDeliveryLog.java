package io.vanillabp.integration.runtime.delivery;

import java.util.Date;
import java.util.Optional;

import org.bson.Document;
import org.eclipse.microprofile.config.ConfigProvider;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.WriteModel;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.delivery.OpenTaskTouches;
import io.vanillabp.integration.adapter.migration.delivery.TaskDeliveryRetentionCleanup;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterPropertiesMapper;
import io.vanillabp.integration.runtime.processservice.PlatformDefaultStore;
import io.vanillabp.integration.runtime.processservice.QuarkusPersistenceTechnology;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * The default {@link TaskDeliveryLog} for Quarkus applications using MongoDB (extension
 * <code>quarkus-mongodb-client</code>) for aggregate persistence. The records live in
 * the collection {@value #DEFAULT_COLLECTION_NAME} of the database
 * <code>quarkus.mongodb.database</code> and are keyed by the delivery key (the
 * document's <code>_id</code>), so uniqueness comes for free.
 * <p>
 * <strong>One transaction where MongoDB Panache provides a session:</strong> the
 * record is written through the <code>ClientSession</code> Panache bound to the running JTA
 * transaction, so it commits with the aggregate and a rollback takes it with it.
 * <p>
 * <strong>Best-effort window (no MongoDB transaction):</strong> Without such a session the
 * record is written IMMEDIATELY instead of with the commit - the same window the MongoDB
 * outbox documents. A record whose transaction rolls back would skip a redelivery of
 * work which never happened, therefore the record is deleted best-effort when the
 * transaction ends in anything but a commit. Only a crash between writing the record and
 * that rollback leaves one behind, and its delivery is then reported as done although the
 * aggregate never changed.
 */
@ApplicationScoped
@Slf4j
public class MongoTaskDeliveryLog implements TaskDeliveryLog, PlatformDefaultStore {

  /**
   * The collection holding the records - the same name the Spring Boot MongoDB log uses,
   * so both platforms share the store layout.
   */
  public static final String DEFAULT_COLLECTION_NAME = "vanillabp-task-deliveries";

  /**
   * The outcome of a delivery which left its task open - the only records the questions
   * about open tasks are interested in.
   */
  private static final String COMPLETION_PENDING = io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome.Kind.COMPLETION_PENDING
      .name();

  @Inject
  Instance<MongoClient> mongoClient;

  @Inject
  TransactionSynchronizationRegistry txRegistry;

  private volatile PhaseTwoOutboxProperties properties;

  private volatile java.time.Duration deliveryRetention;

  private volatile TaskDeliveryRetentionCleanup retentionCleanup;

  private final OpenTaskTouches touches = new OpenTaskTouches(
      DEFAULT_COLLECTION_NAME, this::refreshLastSeen);

  @Override
  public QuarkusPersistenceTechnology.Technology technology() {

    return QuarkusPersistenceTechnology.Technology.MONGO;

  }

  /**
   * Whether this default log is usable: the extension registers the bean at build time,
   * but without a MongoDB client it cannot store anything.
   *
   * @return Whether a MongoDB client is available
   */
  @Override
  public boolean isAvailable() {

    return mongoClient.isResolvable();

  }

  /**
   * The outbox configuration (<code>vanillabp.outbox.*</code>), loaded lazily.
   *
   * @return The configuration
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

  /**
   * How long a record is kept: <code>vanillabp.delivery.retention</code> where the
   * application sets it, and <code>vanillabp.outbox.retention</code> otherwise, which is
   * where the number lived before the two windows were told apart. Loaded lazily like the
   * outbox configuration next to it.
   *
   * Public because it answers the first question a support case about a handler running
   * twice asks, and because it is what the tests of this wiring assert.
   *
   * @return The retention of delivery records
   */
  public java.time.Duration getDeliveryRetention() {

    if (deliveryRetention == null) {
      deliveryRetention = io.vanillabp.integration.adapter.migration.config.DeliveryProperties
          .resolveRetention(
              QuarkusMigrationAdapterPropertiesMapper.INSTANCE
                  .toCore(
                      ConfigProvider
                          .getConfig()
                          .unwrap(SmallRyeConfig.class)
                          .getConfigMapping(QuarkusMigrationAdapterProperties.class)
                          .delivery()),
              getProperties().getRetention());
    }
    return deliveryRetention;

  }

  /**
   * Creates the index the cleanup reads (unless disabled) and starts the cleanup.
   *
   * @param event The startup event observed
   */
  void onStart(
      @Observes final StartupEvent event) {

    if (!isAvailable()) {
      log.debug("No MongoDB client available - the MongoDB-based task delivery log stays inactive");
      return;
    }
    if (!getProperties().getMongo().isEnabled()) {
      log.debug("'vanillabp.outbox.mongo.enabled' is false - the MongoDB-based task delivery log stays inactive");
      return;
    }
    if (getProperties().isCreateSchema()) {
      // MongoDB answers a createIndex of an index which is already there with its name, so
      // two instances starting at the same moment do not collide over it
      deliveryCollection().createIndex(Indexes.ascending("lastSeenAt"));
      // the election of a task operation looks a record up by the task the caller names,
      // once per operation - without this index that read is a collection scan and costs
      // more than the BPMS round trip it saves
      deliveryCollection().createIndex(Indexes.ascending("taskId"));
    }
    retentionCleanup = new TaskDeliveryRetentionCleanup(
        DEFAULT_COLLECTION_NAME, getDeliveryRetention(), this::cleanUpExpiredRecords);
    retentionCleanup.start();

  }

  /**
   * Refreshes the records of the open tasks redelivered since the last run and deletes the
   * records nobody has seen for the retention period - run by the background cleanup and
   * usable on demand (e.g. by tests). Refreshing first is what keeps the record of a task
   * which is still being redelivered.
   *
   * @return The number of records deleted
   */
  public long cleanUpExpiredRecords() {

    touches.flush();

    return deliveryCollection()
        .deleteMany(
            new Document(
                "lastSeenAt", new Document("$lt", Date
                    .from(java.time.Instant.now().minus(getDeliveryRetention())))))
        .getDeletedCount();

  }


  /**
   * Tells the retention cleanup that this store was written to, so its next hourly run has
   * something to delete. Null-safe, because the cleanup is built when the store starts and
   * a store which never started was never written to either.
   */
  private void aDeliveryWasRecorded() {

    final var cleanup = retentionCleanup;
    if (cleanup != null) {
      cleanup.aDeliveryWasRecorded();
    }

  }

  @Override
  public void stillOpen(
      final String deliveryKey) {

    aDeliveryWasRecorded();
    touches.remember(deliveryKey);

  }

  /**
   * Moves <code>lastSeenAt</code> of one block of records, in one round trip. A key whose
   * record was deleted meanwhile matches nothing, which is the right answer: the record is
   * gone and the next redelivery writes a new one.
   *
   * @param deliveryKeys The keys of one block
   */
  private void refreshLastSeen(
      final java.util.List<String> deliveryKeys) {

    final var now = new Date();
    deliveryCollection()
        .bulkWrite(
            deliveryKeys
                .stream()
                .map(deliveryKey -> (WriteModel<Document>) new UpdateOneModel<Document>(
                    Filters.eq("_id", deliveryKey), Updates.set("lastSeenAt", now)))
                .toList(),
            new BulkWriteOptions().ordered(false));

  }

  @PreDestroy
  void shutdown() {

    if (retentionCleanup != null) {
      retentionCleanup.stop();
      retentionCleanup = null;
    }

  }

  @Override
  public Optional<TaskDelivery> recordedDelivery(
      final String deliveryKey) {

    // read through the session of the running transaction where there is one, so the
    // answer is consistent with what this transaction wrote
    final var session = io.vanillabp.integration.runtime.mongo.MongoSessions
        .activeSession(txRegistry);
    final var collection = deliveryCollection();
    return Optional
        .ofNullable(
            session != null
                ? collection
                    .find(session, new Document("_id", deliveryKey))
                    .first()
                : collection
                    .find(new Document("_id", deliveryKey))
                    .first())
        .map(MongoTaskDeliveryLog::recordOf);

  }

  @Override
  public boolean record(
      final TaskDelivery delivery) {

    // what gives the hourly cleanup something to do: a store nobody wrote to has nothing
    // left to delete which an earlier run did not already delete
    aDeliveryWasRecorded();
    final var collection = deliveryCollection();
    // the session of the running transaction where MongoDB Panache provides one: the
    // record then commits with the aggregate instead of being written immediately
    //
    final var session = io.vanillabp.integration.runtime.mongo.MongoSessions
        .activeSession(txRegistry);
    final var recordedAt = Date.from(delivery.recordedAt() == null
        ? java.time.Instant.now()
        : delivery.recordedAt());
    final var record = new Document()
        .append("_id", delivery.deliveryKey())
        // the delivering adapter as a field of its own: the delivery key
        // carries it too, but hashed once the key grows too long
        .append("adapterId", delivery.adapterId())
        .append("workflowModuleId", delivery.workflowModuleId())
        .append("bpmnProcessId", delivery.bpmnProcessId())
        .append("aggregateId", delivery.workflowAggregateId())
        .append("taskDefinition", delivery.taskDefinition())
        // the task the delivery was about: what lets the election answer from this record
        // which adapter holds that task instead of asking every configured BPMS
        .append("taskId", delivery.taskId())
        .append("outcome", delivery.outcome())
        .append("bpmnErrorCode", delivery.bpmnErrorCode())
        .append("bpmnErrorName", delivery.bpmnErrorName())
        .append("recordedAt", recordedAt)
        // the record was seen the moment it was written; a redelivery of a task which
        // stays open moves lastSeenAt and leaves recordedAt where it is
        .append("lastSeenAt", recordedAt);
    // a duplicate-key error inside a MongoDB transaction would abort it entirely, so the
    // common duplicate is read instead - the unique document ID stays the backstop
    if ((session != null) && (collection
        .find(session, new Document("_id", delivery.deliveryKey()))
        .first() != null)) {
      log.debug(
          "Task delivery '{}' of BPMN process '{}' of workflow module '{}' was recorded already",
          delivery.deliveryKey(),
          delivery.bpmnProcessId(),
          delivery.workflowModuleId());
      return false;
    }
    try {
      if (session != null) {
        collection.insertOne(session, record);
      } else {
        collection.insertOne(record);
      }
    } catch (final MongoWriteException e) {
      // 11000 = duplicate key: another node recorded the same delivery concurrently
      if (e.getError().getCode() == 11000) {
        log.debug(
            "Task delivery '{}' of BPMN process '{}' of workflow module '{}' was recorded already",
            delivery.deliveryKey(),
            delivery.bpmnProcessId(),
            delivery.workflowModuleId());
        return false;
      }
      throw e;
    }

    // without a session MongoDB does not take part in the JTA transaction, so the record
    // is already written - remove it again if the transaction does not commit, otherwise a
    // redelivery of the rolled-back work would be skipped. With a session the abort of the
    // MongoDB transaction takes the record with it.
    if ((session == null) && (txRegistry.getTransactionKey() != null)) {
      txRegistry.registerInterposedSynchronization(new Synchronization() {
        @Override
        public void beforeCompletion() {
          // nothing to do
        }

        @Override
        public void afterCompletion(
            final int status) {
          if (status == Status.STATUS_COMMITTED) {
            return;
          }
          try {
            collection.deleteOne(new Document("_id", delivery.deliveryKey()));
          } catch (final RuntimeException e) {
            log.warn(
                "Could not delete the record of task delivery '{}' after the rollback of the local "
                    + "transaction - a redelivery of that task will be skipped although nothing was "
                    + "persisted; delete the record manually",
                delivery.deliveryKey(),
                e);
          }
        }
      });
    }

    return true;

  }

  /**
   * The record which left one task open, whether or not it has been closed since - what the
   * BPMS election of a task operation reads instead of asking a BPMS (see
   * {@link TaskDeliveryLog#recordOfTask}). Read through the session of the running
   * transaction where there is one, and sorted so the most recent record answers where a
   * task was delivered more than once.
   */
  @Override
  public Optional<TaskDelivery> recordOfTask(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String taskId) {

    final var session = io.vanillabp.integration.runtime.mongo.MongoSessions
        .activeSession(txRegistry);
    final var collection = deliveryCollection();
    final var filter = new Document("taskId", taskId)
        .append("workflowModuleId", workflowModuleId)
        .append("bpmnProcessId", bpmnProcessId)
        .append("aggregateId", workflowAggregateId)
        .append("outcome", COMPLETION_PENDING);
    final var newestFirst = new Document("recordedAt", -1);
    return Optional
        .ofNullable(
            session != null
                ? collection
                    .find(session, filter)
                    .sort(newestFirst)
                    .first()
                : collection
                    .find(filter)
                    .sort(newestFirst)
                    .first())
        .map(MongoTaskDeliveryLog::recordOf);

  }

  /**
   * Writes down that the application's completion or cancellation of one task reached the
   * BPMS. The filter demands an absent <code>taskClosedAt</code>, so a repeated dispatch
   * does not move the moment the task was closed.
   */
  @Override
  public int markTaskClosed(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String taskId) {

    final var session = io.vanillabp.integration.runtime.mongo.MongoSessions
        .activeSession(txRegistry);
    final var collection = deliveryCollection();
    final var filter = new Document("taskId", taskId)
        .append("workflowModuleId", workflowModuleId)
        .append("bpmnProcessId", bpmnProcessId)
        .append("aggregateId", workflowAggregateId)
        .append("taskClosedAt", null);
    final var closeIt = Updates.set("taskClosedAt", new Date());
    final var result = session != null
        ? collection.updateOne(session, filter, closeIt)
        : collection.updateOne(filter, closeIt);
    return (int) result.getModifiedCount();

  }

  /**
   * The record one document holds.
   *
   * @param document The document read
   * @return What VanillaBP remembers about that delivery
   */
  private static TaskDelivery recordOf(
      final Document document) {

    return new TaskDelivery(
        document.getString("_id"), document.getString("adapterId"), document
            .getString("workflowModuleId"), document.getString("bpmnProcessId"), document
                .getString("aggregateId"), document.getString("taskDefinition"), document
                    .getString("taskId"), document.getString("outcome"), document
                        .getString("bpmnErrorCode"), document.getString("bpmnErrorName"), instantOf(
                            document.getDate("recordedAt")), instantOf(document.getDate("taskClosedAt")));

  }

  /**
   * @param date A moment the document holds or <code>null</code>
   * @return The same moment, or <code>null</code>
   */
  private static java.time.Instant instantOf(
      final Date date) {

    return date == null
        ? null
        : date.toInstant();

  }

  /**
   * The adapter ids the OPEN records of one BPMN process belong to: asked once
   * per BPMN process at startup.
   */
  @Override
  public java.util.Set<String> adapterIdsOfOpenTasks(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var filter = new Document("workflowModuleId", workflowModuleId)
        .append("bpmnProcessId", bpmnProcessId)
        .append("outcome", COMPLETION_PENDING)
        .append("adapterId", new Document("$ne", null));
    final var adapterIds = new java.util.LinkedHashSet<String>();
    deliveryCollection()
        .distinct("adapterId", filter, String.class)
        .forEach(adapterIds::add);
    return adapterIds;

  }

  @Override
  public Boolean hasOpenRecords(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var filter = new Document("workflowModuleId", workflowModuleId)
        .append("bpmnProcessId", bpmnProcessId)
        .append("outcome", COMPLETION_PENDING);
    return deliveryCollection().countDocuments(filter) > 0;

  }

  @Override
  public int releaseRecordsOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final java.time.Instant recordedBefore) {

    final var collection = deliveryCollection();
    // through the session of the running transaction where MongoDB Panache provides one:
    // the deletion then commits with the end notification instead of being written
    // immediately
    final var session = io.vanillabp.integration.runtime.mongo.MongoSessions
        .activeSession(txRegistry);
    final var filter = new Document()
        .append("workflowModuleId", workflowModuleId)
        .append("bpmnProcessId", bpmnProcessId)
        .append("aggregateId", workflowAggregateId)
        .append("recordedAt", new Document("$lt", Date.from(recordedBefore)));
    final var result = session != null
        ? collection.deleteMany(session, filter)
        : collection.deleteMany(filter);
    return (int) result.getDeletedCount();

  }

  private MongoCollection<Document> deliveryCollection() {

    final var database = ConfigProvider
        .getConfig()
        .getOptionalValue("quarkus.mongodb.database", String.class)
        .orElseThrow(() -> new IllegalStateException(
            """
                The MongoDB-based task delivery log needs the database name! Set the property \
                'quarkus.mongodb.database' (the same database the workflow aggregates live in)."""));
    return mongoClient
        .get()
        .getDatabase(database)
        .getCollection(DEFAULT_COLLECTION_NAME);

  }

}
