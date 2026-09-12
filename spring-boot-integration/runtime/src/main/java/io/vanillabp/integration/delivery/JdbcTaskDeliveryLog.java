package io.vanillabp.integration.delivery;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.adapter.migration.delivery.TaskDeliveryRetentionCleanup;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;

/**
 * The default {@link TaskDeliveryLog} for Spring Boot applications persisting their
 * workflow aggregates via JPA/JDBC: the records are written through a connection
 * {@link DataSourceUtils} binds to the Spring-managed transaction, so a record and the
 * aggregate changes of the same delivery commit together - the recording contract of
 * {@link TaskDeliveryLog}.
 * <p>
 * Unlike the phase-two outbox this does NOT use gruelbox: gruelbox stores calls to be
 * dispatched, while a delivery record is a fact to be read back. The table
 * ({@link JdbcTaskDeliveryStore#DEFAULT_TABLE_NAME}) is VanillaBP's own and is created
 * at startup unless <code>vanillabp.outbox.create-schema</code> is disabled.
 */
public class JdbcTaskDeliveryLog implements TaskDeliveryLog, JdbcConnectionAccess {

  private final DataSource dataSource;

  private final JdbcTaskDeliveryStore store;

  private final java.time.Duration retention;

  private final TaskDeliveryRetentionCleanup retentionCleanup;

  public JdbcTaskDeliveryLog(
      final DataSource dataSource,
      final String tableName,
      final Duration retention) {

    this.dataSource = dataSource;
    this.store = new JdbcTaskDeliveryStore(this, tableName);
    this.retention = retention;
    this.retentionCleanup = new TaskDeliveryRetentionCleanup(
        tableName, retention, this::cleanUpExpiredRecords);

  }

  /**
   * Creates the table and starts the retention cleanup.
   *
   * @param createSchema Whether VanillaBP creates the table itself
   */
  public void start(
      final boolean createSchema) {

    if (createSchema) {
      store.createSchemaIfNotExists();
    } else {
      // the application creates its schema itself - then a missing table is a
      // deployment which forgot to apply the migration, and it is said at startup
      store.validateSchemaExists();
    }
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

    return store.deleteExpired(retention);

  }

  @Override
  public void stillOpen(
      final String deliveryKey) {

    retentionCleanup.aDeliveryWasRecorded();
    store.stillOpen(deliveryKey);

  }

  /**
   * Stops the retention cleanup - called on shutdown of the application context.
   */
  public void stop() {

    retentionCleanup.stop();

  }

  @Override
  public Optional<TaskDelivery> recordedDelivery(
      final String deliveryKey) {

    return store.recordedDelivery(deliveryKey);

  }

  @Override
  public boolean record(
      final TaskDelivery delivery) {

    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          """
              No transaction active! A task delivery has to be recorded within the still-running \
              transaction persisting the workflow aggregate - a record committed on its own would \
              skip the @WorkflowTask method of a redelivery although nothing was persisted.""");
    }
    // what gives the hourly cleanup something to do: a store nobody wrote to has nothing
    // left to delete which an earlier run did not already delete
    retentionCleanup.aDeliveryWasRecorded();
    return store.record(delivery);

  }

  /**
   * The adapter ids the OPEN records of one BPMN process belong to - the
   * shared store answers it with one query over the columns it indexes anyway.
   */
  @Override
  public java.util.Set<String> adapterIdsOfOpenTasks(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return store.adapterIdsOfOpenTasks(workflowModuleId, bpmnProcessId);

  }

  @Override
  public Boolean hasOpenRecords(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return store.hasOpenRecords(workflowModuleId, bpmnProcessId);

  }

  /**
   * The record which left one task open - read within the caller's transaction, through the
   * connection bound to it, so it sees what that transaction has written itself.
   */
  @Override
  public Optional<TaskDelivery> recordOfTask(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String taskId) {

    return store.recordOfTask(workflowModuleId, bpmnProcessId, workflowAggregateId, taskId);

  }

  @Override
  public int markTaskClosed(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String taskId) {

    return store.markTaskClosed(workflowModuleId, bpmnProcessId, workflowAggregateId, taskId);

  }

  @Override
  public int releaseRecordsOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final java.time.Instant recordedBefore) {

    return store
        .deleteRecordsOf(workflowModuleId, bpmnProcessId, workflowAggregateId, recordedBefore);

  }

  @Override
  public Connection acquire() throws SQLException {

    // bound to the Spring-managed transaction if one is running (which the recording
    // contract requires) - a plain connection of the pool otherwise, used by reads
    return DataSourceUtils.getConnection(dataSource);

  }

  @Override
  public void release(
      final Connection connection) {

    DataSourceUtils.releaseConnection(connection, dataSource);

  }

}
