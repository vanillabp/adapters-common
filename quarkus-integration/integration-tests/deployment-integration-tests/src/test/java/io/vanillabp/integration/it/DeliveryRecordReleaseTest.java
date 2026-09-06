package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.SQLException;
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
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.delivery.DeliveryAggregate;
import io.vanillabp.integration.test.delivery.DeliveryAggregatePersistence;
import io.vanillabp.integration.test.delivery.DeliveryProcessWiringSource;
import io.vanillabp.integration.test.delivery.DeliveryWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.WorkflowEnd;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Acceptance test of the release of delivery records on Quarkus, with the
 * default JDBC-based delivery log: a workflow which ended deletes the records of its
 * processed deliveries instead of leaving them to the retention. The workflow service of
 * this test has NO <code>&#64;WorkflowEnded</code> method on purpose - the end
 * notification is asked for by the release alone, which is what makes the feature work
 * without touching any adapter.
 * <p>
 * Pinned as well: the record of a SECOND workflow on the same aggregate, written after the
 * end of the first one, survives, and the records of another workflow stay untouched. The
 * case with the option switched off is pinned by {@link InboundIdempotencyTest}, whose
 * application does not configure the release.
 */
@ExtendWith(SuppressOutputExtension.class)
public class DeliveryRecordReleaseTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "DeliveryProcess";

  private static final String ADAPTER = "demo1";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("delivery-release/application.yaml", "application.yaml")
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
      public String getDeliveryId() {
        return deliveryId;
      }

    };

  }

  /**
   * The end of a workflow, as an adapter's process-end listener reports it.
   */
  private WorkflowEndedContext workflowEnded(
      final String aggregateId) {

    return new WorkflowEndedContext() {

      @Override
      public String getWorkflowAggregateId() {
        return aggregateId;
      }

      @Override
      public WorkflowEnd.Kind getKind() {
        return WorkflowEnd.Kind.COMPLETED;
      }

      @Override
      public Instant getEndTime() {
        return Instant.now();
      }

      @Override
      public String getEndEventId() {
        return "Event_Done";
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

  @Test
  @DisplayName("An ended workflow releases its records, and a second workflow on the same aggregate keeps its own")
  public void anEndedWorkflowReleasesItsRecords() throws SQLException {

    final var dummyAdapter = dummyAdapter();
    assertEquals(
        List.of(PROCESS),
        dummyAdapter.getProcessesWithEndListener(),
        "the release needs the end of a workflow, so the listener is attached without a @WorkflowEnded method");

    persistence.store("4711");
    persistence.store("4712");
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("processTask", "4711", "job-1"));
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("processTask", "4711", "job-2"));
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("processTask", "4712", "job-3"));
    assertEquals(2, recordCount("4711"));
    assertEquals(1, recordCount("4712"));

    dummyAdapter.notifyWorkflowEnded(MODULE, PROCESS, workflowEnded("4711"));

    assertEquals(0, recordCount("4711"), "the ended workflow releases its records");
    assertEquals(1, recordCount("4712"), "another workflow keeps its own");

    // a SECOND workflow on the same aggregate: its delivery is processed after the end of
    // the first one, so its record was written after the notification and stays
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("processTask", "4711", "job-4"));
    assertEquals(1, recordCount("4711"));

    final var repeated = dummyAdapter.invokeTask(MODULE, PROCESS, delivery("processTask", "4711", "job-4"));
    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, repeated.kind());
    assertEquals(
        3,
        persistence.get("4711").getInvocations(),
        "the released records must not make a repeated delivery run the handler again");

  }

}
