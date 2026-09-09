package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.test.secondary.OrderAggregate;
import io.vanillabp.integration.test.secondary.OrderAggregatePersistence;
import io.vanillabp.integration.test.secondary.OrderAwarenessSource;
import io.vanillabp.integration.test.secondary.OrderOutbox;
import io.vanillabp.integration.test.secondary.OrderOutboxAware;
import io.vanillabp.integration.test.secondary.OrderWorkflowService;
import io.vanillabp.integration.test.secondary.OrderingAndShippingWiringSource;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.process.WorkflowNotFoundException;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * What a called process delivered is found under the id the application calls, on Quarkus:
 * this is the platform half of the change, and what it proves is that the ids a workflow
 * service serves really reach the two readers of the core.
 * <p>
 * The scenario is the everyday one. A workflow service serves {@code Ordering} and
 * declares {@code Shipping} as a secondary process, the BPMS hands out a task of
 * {@code Shipping}, and the application completes that task through the only process
 * service it ever sees, the one of {@code Ordering}. The delivery wrote two things under
 * {@code Shipping}: the record naming the adapter which delivered, and the hint saying
 * which BPMS holds this workflow. Both used to be invisible to the primary service, so
 * every operation on such a task paid a probe, no visibility window was ever waited out,
 * and the row of the task stayed open forever.
 */
@ExtendWith(SuppressOutputExtension.class)
public class SecondaryProcessDeliveryTest {

  private static final String MODULE = "secondary-module";

  private static final String SECONDARY_PROCESS = "Shipping";

  private static final String ADAPTER = "demo1";

  /**
   * The workflow of the test which completes a task. Every test brings an aggregate of
   * its own: the tests of this class share one application, and an election which walked
   * the adapters leaves a hint under the PRIMARY id behind - which would answer the next
   * test without anybody reading the called process' one.
   */
  private static final String COMPLETING_AGGREGATE = "4711";

  /**
   * The workflow of the test which correlates a message, and which nothing but the
   * delivery of the called process ever reported.
   */
  private static final String CORRELATING_AGGREGATE = "4712";

  /**
   * An aggregate nobody ever delivered a task for - the id which really is wrong.
   */
  private static final String AGGREGATE_OF_NO_WORKFLOW = "4713";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("secondary-delivery/application.yaml", "application.yaml")
          .addClass(OrderAggregate.class)
          .addClass(OrderAggregatePersistence.class)
          .addClass(OrderWorkflowService.class)
          .addClass(OrderingAndShippingWiringSource.class)
          .addClass(OrderAwarenessSource.class)
          .addClass(OrderOutbox.class)
          .addClass(OrderOutboxAware.class)
          .addAsResource("bpmn/first.bpmn", "processes/secondary/Ordering.bpmn")
          .addAsResource("secondary-delivery/workflow-module", "META-INF/workflow-module"));

  @Inject
  OrderAggregatePersistence persistence;

  @Inject
  OrderAwarenessSource awareness;

  @Inject
  OrderOutbox outbox;

  @Inject
  PhaseTwoRouter router;

  @Inject
  ProcessService<OrderAggregate> processService;

  @Inject
  UserTransaction userTransaction;

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

  /**
   * A delivery of the called process, as its adapter reports it.
   */
  private TaskInvocationContext deliveryOfTheCalledProcess(
      final String workflowAggregateId,
      final String taskId) {

    return new TaskInvocationContext() {

      @Override
      public String getAdapterId() {
        return ADAPTER;
      }

      @Override
      public String getTaskDefinition() {
        return "awaitShipment";
      }

      @Override
      public String getWorkflowAggregateId() {
        return workflowAggregateId;
      }

      @Override
      public String getTaskId() {
        return taskId;
      }

      @Override
      public String getDeliveryId() {
        return "job-of-"
            + taskId;
      }

    };

  }

  /**
   * The moment the row of the given task carries, or <code>null</code> while the task is
   * open.
   */
  private Object taskClosedAt(
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String taskId) throws SQLException {

    try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
        """
            SELECT TASK_CLOSED_AT FROM %s \
            WHERE BPMN_PROCESS_ID = ? AND TASK_ID = ? AND AGGREGATE_ID = ?"""
            .formatted(JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME))) {
      statement.setString(1, bpmnProcessId);
      statement.setString(2, taskId);
      statement.setString(3, workflowAggregateId);
      try (var resultSet = statement.executeQuery()) {
        assertTrue(resultSet.next(), "the delivery of the called process wrote no record at all");
        return resultSet.getObject(1);
      }
    }

  }

  private void inATransaction(
      final Runnable work) throws Exception {

    userTransaction.begin();
    try {
      work.run();
      userTransaction.commit();
    } catch (final RuntimeException e) {
      userTransaction.rollback();
      throw e;
    }

  }

  @Test
  @DisplayName("A task the called process delivered is completed on the primary service, and its row is closed")
  public void aTaskOfTheCalledProcessIsCompletedWithoutProbingAnybody() throws Exception {

    outbox.clear();
    persistence.store(COMPLETING_AGGREGATE);
    awareness.answerWith(WorkflowAwareness.ACTIVE);

    dummyAdapter()
        .invokeTask(MODULE, SECONDARY_PROCESS, deliveryOfTheCalledProcess(COMPLETING_AGGREGATE, "shipment-1"));
    awareness.forgetTheProbesSoFar();

    inATransaction(() -> processService.completeTask(persistence.get(COMPLETING_AGGREGATE), "shipment-1"));

    assertEquals(
        0,
        awareness.probes(),
        "the record of the called process answered the election, so no BPMS was asked");

    // and the moment the completion reaches the BPMS the row is closed - under the id the
    // delivery wrote it, which is the called process
    router.dispatch(outbox.planned().getLast());

    assertNotNull(
        taskClosedAt(SECONDARY_PROCESS, COMPLETING_AGGREGATE, "shipment-1"),
        "the row nobody found used to stay open, counting as an open task for good");

  }

  @Test
  @DisplayName("A workflow only the called process reported is planned rather than refused")
  public void theHintOfTheCalledProcessTellsAnUnknownWorkflowFromAWrongId() throws Exception {

    outbox.clear();
    persistence.store(CORRELATING_AGGREGATE);
    // the read model of the BPMS has not caught up: nobody reports this workflow
    awareness.answerWith(WorkflowAwareness.UNKNOWN_TO_BPMS);

    // an aggregate nobody ever delivered a task for: the id is simply wrong, and the
    // caller hears it at once
    persistence.store(AGGREGATE_OF_NO_WORKFLOW);
    assertThrows(
        WorkflowNotFoundException.class,
        () -> inATransaction(() -> processService
            .correlateMessage(persistence.get(AGGREGATE_OF_NO_WORKFLOW), "ShipmentArrived")));

    // the same answer from the BPMS, but this workflow was reported by a delivery of the
    // called process - so it exists, and the operation is planned
    dummyAdapter()
        .invokeTask(MODULE, SECONDARY_PROCESS, deliveryOfTheCalledProcess(CORRELATING_AGGREGATE, "shipment-2"));

    inATransaction(() -> processService.correlateMessage(persistence.get(CORRELATING_AGGREGATE), "ShipmentArrived"));

    assertTrue(
        outbox
            .planned()
            .stream()
            .anyMatch(call -> call.workflowAggregateId().equals(CORRELATING_AGGREGATE)),
        "the hint the delivery wrote under the called process is what tells a workflow which "
            + "is not visible yet from an id nobody ever started");

  }

}
