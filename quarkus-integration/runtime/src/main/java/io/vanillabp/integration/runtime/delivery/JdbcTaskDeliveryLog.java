package io.vanillabp.integration.runtime.delivery;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

import javax.sql.DataSource;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
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
import jakarta.transaction.TransactionSynchronizationRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * The default {@link TaskDeliveryLog} for Quarkus applications persisting their workflow
 * aggregates in a relational database: the records are written through a JDBC connection
 * of the Agroal data source. Since the connection is acquired within the still-running
 * JTA transaction it is enlisted automatically, so a record and the aggregate changes of
 * the same delivery commit together - the recording contract of {@link TaskDeliveryLog}.
 * <p>
 * The table ({@link JdbcTaskDeliveryStore#DEFAULT_TABLE_NAME}) is created at startup
 * unless <code>vanillabp.outbox.create-schema</code> is disabled, and records are
 * deleted once <code>vanillabp.delivery.retention</code> passed - the delivery log shares
 * the outbox' STORE settings and not its retention, because the two numbers mean different
 * things: for a record the retention decides whether a late redelivery runs the business
 * code again, for a dispatched outbox entry only how long support can read it.
 */
@ApplicationScoped
@Slf4j
public class JdbcTaskDeliveryLog implements TaskDeliveryLog, JdbcConnectionAccess, PlatformDefaultStore {

  @Inject
  Instance<DataSource> dataSource;

  @Inject
  TransactionSynchronizationRegistry txRegistry;

  private volatile PhaseTwoOutboxProperties properties;

  private volatile java.time.Duration deliveryRetention;

  private volatile JdbcTaskDeliveryStore store;

  private volatile TaskDeliveryRetentionCleanup retentionCleanup;

  @Override
  public QuarkusPersistenceTechnology.Technology technology() {

    return QuarkusPersistenceTechnology.Technology.JPA;

  }

  /**
   * Whether this default log is usable: the extension registers the bean at build time,
   * but without a configured datasource it cannot store anything - an unusable default
   * must not be selected for an aggregate (the startup validation then reports that
   * deliveries are not deduplicated, naming the remedies).
   *
   * @return Whether a datasource is available
   */
  @Override
  public boolean isAvailable() {

    return dataSource.isResolvable();

  }

  /**
   * The outbox configuration (<code>vanillabp.outbox.*</code>), loaded lazily - the log
   * may be asked for a record before the startup event was observed.
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

  private JdbcTaskDeliveryStore getStore() {

    if (store == null) {
      store = new JdbcTaskDeliveryStore(this, JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME);
    }
    return store;

  }

  /**
   * Creates the table (unless disabled) and starts the retention cleanup.
   *
   * @param event The startup event observed
   */
  void onStart(
      @Observes final StartupEvent event) {

    if (!isAvailable()) {
      log.debug("No datasource available - the JDBC-based task delivery log stays inactive");
      return;
    }
    if (!getProperties().getJdbc().isEnabled()) {
      log.debug("'vanillabp.outbox.jdbc.enabled' is false - the JDBC-based task delivery log stays inactive");
      return;
    }
    if (getProperties().isCreateSchema()) {
      getStore().createSchemaIfNotExists();
    } else {
      // the application creates its schema itself - then a missing table is a
      // deployment which forgot to apply the migration, and it is said at startup
      getStore().validateSchemaExists();
    }
    retentionCleanup = new TaskDeliveryRetentionCleanup(
        getStore().getTableName(), getDeliveryRetention(), this::cleanUpExpiredRecords);
    retentionCleanup.start();

  }

  /**
   * Refreshes the records of the open tasks redelivered since the last run and deletes the
   * records whose retention period passed - run by the background cleanup and usable on
   * demand (e.g. by tests). Both are the store's business and happen in that order.
   *
   * @return The number of records deleted
   */
  public int cleanUpExpiredRecords() {

    return getStore().deleteExpired(getDeliveryRetention());

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
    getStore().stillOpen(deliveryKey);

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

    return getStore().recordedDelivery(deliveryKey);

  }

  @Override
  public boolean record(
      final TaskDelivery delivery) {

    if (txRegistry.getTransactionKey() == null) {
      throw new IllegalStateException(
          """
              No transaction active! A task delivery has to be recorded within the still-running \
              transaction persisting the workflow aggregate - a record committed on its own would \
              skip the @WorkflowTask method of a redelivery although nothing was persisted.""");
    }
    // what gives the hourly cleanup something to do: a store nobody wrote to has nothing
    // left to delete which an earlier run did not already delete
    aDeliveryWasRecorded();
    return getStore().record(delivery);

  }

  /**
   * The adapter ids the OPEN records of one BPMN process belong to - the
   * shared store answers it with one query over the columns it indexes anyway.
   */
  @Override
  public java.util.Set<String> adapterIdsOfOpenTasks(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return isAvailable()
        ? getStore().adapterIdsOfOpenTasks(workflowModuleId, bpmnProcessId)
        : java.util.Set.of();

  }

  @Override
  public Boolean hasOpenRecords(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return isAvailable()
        ? getStore().hasOpenRecords(workflowModuleId, bpmnProcessId)
        : null;

  }

  /**
   * The record which left one task open - read within the caller's JTA transaction, through
   * a connection Agroal enlists there, so it sees what that transaction has written itself.
   */
  @Override
  public Optional<TaskDelivery> recordOfTask(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String taskId) {

    return isAvailable()
        ? getStore().recordOfTask(workflowModuleId, bpmnProcessId, workflowAggregateId, taskId)
        : Optional.empty();

  }

  @Override
  public int markTaskClosed(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String taskId) {

    return isAvailable()
        ? getStore().markTaskClosed(workflowModuleId, bpmnProcessId, workflowAggregateId, taskId)
        : 0;

  }

  @Override
  public int releaseRecordsOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final java.time.Instant recordedBefore) {

    return getStore()
        .deleteRecordsOf(workflowModuleId, bpmnProcessId, workflowAggregateId, recordedBefore);

  }

  @Override
  public Connection acquire() throws SQLException {

    if (!dataSource.isResolvable()) {
      throw new IllegalStateException(
          """
              No datasource available! The JDBC-based task delivery log requires a configured default \
              datasource (quarkus-agroal).""");
    }
    // acquired within the running JTA transaction, so Agroal enlists it there
    return dataSource.get().getConnection();

  }

}
