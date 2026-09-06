package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.runtime.delivery.JdbcTaskDeliveryLog;
import io.vanillabp.integration.test.delivery.DeliveryAggregate;
import io.vanillabp.integration.test.delivery.DeliveryAggregatePersistence;
import io.vanillabp.integration.test.delivery.DeliveryProcessWiringSource;
import io.vanillabp.integration.test.delivery.DeliveryWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Acceptance test on Quarkus, with the default JDBC-based delivery log: the
 * record which answers the redeliveries of an OPEN task outlives the retention as long as
 * the BPMS keeps redelivering that task, while the record of a task nobody hands out any
 * more expires as it always did. The age of the open task keeps being measured from the
 * moment the handler ran, so <code>vanillabp.delivery.max-task-age</code> still fires.
 * <p>
 * An hour of retention and an hour of maximum age, with the records backdated by two -
 * nothing here waits for a clock.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OpenTaskRetentionTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "DeliveryProcess";

  private static final String ADAPTER = "demo1";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("open-task/application.yaml", "application.yaml")
          .addClass(DeliveryAggregate.class)
          .addClass(DeliveryAggregatePersistence.class)
          .addClass(DeliveryWorkflowService.class)
          .addClass(DeliveryProcessWiringSource.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/DeliveryProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  DeliveryAggregatePersistence persistence;

  @Inject
  DataSource dataSource;

  @Inject
  JdbcTaskDeliveryLog deliveryLog;

  @Inject
  @Any
  Instance<List<AdapterDeploymentService<Object, Object>>> deploymentServices;

  private DummyDeploymentService dummyAdapter() {

    return deploymentServices
        .stream()
        .filter(java.util.Objects::nonNull)
        .flatMap(List::stream)
        .filter(java.util.Objects::nonNull)
        .filter(DummyDeploymentService.class::isInstance)
        .map(DummyDeploymentService.class::cast)
        .filter(service -> ADAPTER.equals(service.getAdapterId()))
        .findFirst()
        .orElseThrow();

  }

  private TaskInvocationContext delivery(
      final String taskDefinition,
      final String aggregateId,
      final String deliveryId) {

    return new TaskInvocationContext() {

      @Override
      public String getAdapterId() {
        return ADAPTER;
      }

      @Override
      public String getTaskDefinition() {
        return taskDefinition;
      }

      @Override
      public String getWorkflowAggregateId() {
        return aggregateId;
      }

      @Override
      public String getTaskId() {
        return deliveryId;
      }

      @Override
      public String getDeliveryId() {
        return deliveryId;
      }

    };

  }

  private int recordCount(
      final String aggregateId) throws SQLException {

    try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
        "SELECT COUNT(*) FROM %s WHERE AGGREGATE_ID = ?".formatted(JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME))) {
      statement.setString(1, aggregateId);
      try (var resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }

  }

  /**
   * Moves every record of the table back in time, both of its timestamps - a database two
   * hours older than the application which reads it.
   */
  private void backdateEveryRecordBy(
      final Duration age) throws SQLException {

    final var moment = Timestamp.from(Instant.now().minus(age));
    try (var connection = dataSource.getConnection(); var statement = connection
        .prepareStatement("UPDATE %s SET RECORDED_AT = ?, LAST_SEEN_AT = ?"
            .formatted(JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME))) {
      statement.setTimestamp(1, moment);
      statement.setTimestamp(2, moment);
      statement.executeUpdate();
    }

  }

  private Timestamp lastSeenAt(
      final String aggregateId) throws SQLException {

    try (var connection = dataSource.getConnection(); var statement = connection
        .prepareStatement("SELECT LAST_SEEN_AT FROM %s WHERE AGGREGATE_ID = ?"
            .formatted(JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME))) {
      statement.setString(1, aggregateId);
      try (var resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getTimestamp(1);
      }
    }

  }

  @Test
  @DisplayName("The record of an open task survives the retention, the record of a finished one does not")
  public void theRecordOfAnOpenTaskSurvivesTheRetention() throws SQLException {

    final var dummyAdapter = dummyAdapter();

    // an asynchronous task the application never completes, and an ordinary one which is
    // done the moment its handler returns
    persistence.store("4711");
    persistence.store("4712");
    final var opened = dummyAdapter.invokeTask(MODULE, PROCESS, delivery("awaitCompletion", "4711", "job-1"));
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("processTask", "4712", "job-2"));
    assertEquals(WorkflowTaskOutcome.Kind.COMPLETION_PENDING, opened.kind());
    assertEquals(1, recordCount("4711"));
    assertEquals(1, recordCount("4712"));

    // two hours later, an hour past the retention and past the maximum age
    backdateEveryRecordBy(Duration.ofHours(2));
    final var backdated = lastSeenAt("4711");

    // the BPMS renews the lock of the task nobody completed, which is a redelivery
    final var redelivered = dummyAdapter.invokeTask(MODULE, PROCESS, delivery("awaitCompletion", "4711", "job-1"));
    assertEquals(WorkflowTaskOutcome.Kind.COMPLETION_PENDING, redelivered.kind());
    assertEquals(
        1,
        persistence.get("4711").getInvocations(),
        "the redelivery is answered from the record, the handler ran once");
    assertTrue(
        redelivered.openFor().compareTo(Duration.ofHours(2)) >= 0,
        "the age is still measured from the moment the handler ran: "
            + redelivered.openFor());
    assertTrue(
        redelivered.maxAgeExceeded(),
        "so a task open longer than 'vanillabp.delivery.max-task-age' is still reported");

    // the cleanup writes what the redelivery collected and then deletes what nobody saw
    final var deleted = deliveryLog.cleanUpExpiredRecords();

    assertEquals(1, deleted);
    assertEquals(
        1,
        recordCount("4711"),
        "the task is still being redelivered, so the record answering it stays");
    assertEquals(
        0,
        recordCount("4712"),
        "nobody redelivers a task which is done - its record expires as it always did");
    assertTrue(lastSeenAt("4711").after(backdated), "the moment the record was last seen moved forward");

    // and the record kept alive still does its job
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("awaitCompletion", "4711", "job-1"));
    assertEquals(
        1,
        persistence.get("4711").getInvocations(),
        "which is the whole point: the handler of an open task must not run twice");

  }

}
