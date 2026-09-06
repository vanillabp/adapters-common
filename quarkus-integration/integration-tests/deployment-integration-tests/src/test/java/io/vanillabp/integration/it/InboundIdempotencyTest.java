package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;

import javax.sql.DataSource;

import org.jboss.logmanager.formatters.PatternFormatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.delivery.DeliveryAggregate;
import io.vanillabp.integration.test.delivery.DeliveryAggregatePersistence;
import io.vanillabp.integration.test.delivery.DeliveryProcessWiringSource;
import io.vanillabp.integration.test.delivery.DeliveryWorkflowService;
import io.vanillabp.integration.test.deployment.TestMeterRegistryProducer;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Acceptance test of the inbound idempotency on Quarkus, with the default
 * JDBC-based delivery log doing the remembering: the dummy adapter delivers a task TWICE
 * under the same delivery identity - as a BPMS which never learned the result does - and
 * the <code>&#64;WorkflowTask</code> method has to run once while both deliveries are
 * answered with the same outcome. Also pinned: a delivery whose handler threw leaves no
 * record (its retry runs the handler again), the task-level switch turns the feature
 * off, and two deliveries of one task which overlap each other are named in the log and
 * counted, since a record written after the work cannot prevent that case.
 */
@ExtendWith(SuppressOutputExtension.class)
public class InboundIdempotencyTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "DeliveryProcess";

  private static final String ADAPTER = "demo1";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("inbound-idempotency/application.yaml", "application.yaml")
          .addClass(DeliveryAggregate.class)
          .addClass(DeliveryAggregatePersistence.class)
          .addClass(DeliveryWorkflowService.class)
          .addClass(DeliveryProcessWiringSource.class)
          .addClass(TestMeterRegistryProducer.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/DeliveryProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  DeliveryAggregatePersistence persistence;

  @Inject
  DataSource dataSource;

  @Inject
  SimpleMeterRegistry meterRegistry;

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
   * One delivery of a task, as an adapter of a remote BPMS builds it: the delivery ID is
   * what stays the same when the BPMS repeats it.
   */
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
  @DisplayName("A repeated delivery runs the handler once and reports the recorded outcome")
  public void repeatedDeliveriesRunTheHandlerOnce() throws SQLException {

    final var dummyAdapter = dummyAdapter();

    persistence.store("4711");
    final var first = dummyAdapter.invokeTask(MODULE, PROCESS, delivery("processTask", "4711", "job-1"));
    final var second = dummyAdapter.invokeTask(MODULE, PROCESS, delivery("processTask", "4711", "job-1"));

    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, first.kind());
    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, second.kind());
    assertEquals(1, persistence.get("4711").getInvocations());
    assertEquals(1, recordCount("4711"));

    // the next task instance of the same workflow is another delivery
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("processTask", "4711", "job-2"));
    assertEquals(2, persistence.get("4711").getInvocations());
    assertEquals(2, recordCount("4711"));

  }

  @Test
  @DisplayName("A repeated delivery of a BPMN error reports code and name again")
  public void repeatedDeliveryReportsTheRecordedBpmnError() {

    final var dummyAdapter = dummyAdapter();

    persistence.store("4712");
    final var error = dummyAdapter.invokeTask(MODULE, PROCESS, delivery("raiseBpmnError", "4712", "job-3"));
    final var errorAgain = dummyAdapter.invokeTask(MODULE, PROCESS, delivery("raiseBpmnError", "4712", "job-3"));

    assertEquals(WorkflowTaskOutcome.Kind.BPMN_ERROR, errorAgain.kind());
    assertEquals(error.errorCode(), errorAgain.errorCode());
    assertEquals("PAYMENT_FAILED", errorAgain.errorCode());
    assertEquals("PaymentFailed", errorAgain.errorName());
    assertEquals(1, persistence.get("4712").getInvocations());

  }

  @Test
  @DisplayName("A rolled-back delivery leaves no record, so its retry runs the handler")
  public void aRolledBackDeliveryLeavesNoRecord() throws SQLException {

    final var dummyAdapter = dummyAdapter();

    persistence.store("4713");
    assertThrows(
        IllegalStateException.class,
        () -> dummyAdapter.invokeTask(MODULE, PROCESS, delivery("failTask", "4713", "job-4")));
    assertEquals(0, recordCount("4713"));

    assertThrows(
        IllegalStateException.class,
        () -> dummyAdapter.invokeTask(MODULE, PROCESS, delivery("failTask", "4713", "job-4")));
    assertEquals(0, recordCount("4713"));

  }

  @Test
  @DisplayName("Without the release nobody asks for the end of a workflow")
  public void noEndListenerWithoutTheRelease() {

    assertEquals(
        List.of(),
        dummyAdapter().getProcessesWithEndListener(),
        "a model must not pay for a listener nobody uses");

  }

  @Test
  @DisplayName("Switched off for a task, nothing is remembered")
  public void aTaskMayOptOut() throws SQLException {

    final var dummyAdapter = dummyAdapter();

    persistence.store("4714");
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("undeduplicatedTask", "4714", "job-5"));
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("undeduplicatedTask", "4714", "job-5"));

    assertEquals(2, persistence.get("4714").getInvocations());
    assertEquals(0, recordCount("4714"));

  }

  /**
   * What the application logged while one test ran. The captured output of
   * {@link SuppressOutputExtension} is not available here: a method of a
   * {@link QuarkusExtensionTest} is invoked by reflection in the application's class
   * loader, so a resolved parameter of it never arrives. The WARN is therefore read
   * where it is written, from a handler on the log manager this module runs under.
   */
  private static final List<String> LOGGED_WHILE_THE_TEST_RAN = new CopyOnWriteArrayList<>();

  private static final Handler COLLECTING_WHAT_IS_LOGGED = new Handler() {

    private final PatternFormatter asItReads = new PatternFormatter("%s");

    @Override
    public void publish(
        final LogRecord logged) {

      LOGGED_WHILE_THE_TEST_RAN.add(asItReads.format(logged));

    }

    @Override
    public void flush() {

    }

    @Override
    public void close() {

    }

  };

  @Test
  @DisplayName("Two deliveries at the same time run the handler twice, which is said and counted")
  public void twoDeliveriesAtTheSameTimeAreNamedAndCounted() throws Exception {

    final var dummyAdapter = dummyAdapter();

    final var firstHandlerIsRunning = new CountDownLatch(1);
    final var secondDeliveryCommitted = new CountDownLatch(1);
    final var handlerRuns = new AtomicInteger();
    // the first delivery to arrive stays in the handler until the second one is
    // through, which is the overlap a real BPMS produces when its lock is too short
    DeliveryWorkflowService.WHILE_THE_CONCURRENT_TASK_RUNS.set(() -> {
      if (handlerRuns.incrementAndGet() > 1) {
        return;
      }
      firstHandlerIsRunning.countDown();
      try {
        secondDeliveryCommitted.await(30, TimeUnit.SECONDS);
      } catch (final InterruptedException e) {
        Thread
            .currentThread()
            .interrupt();
      }
    });

    LOGGED_WHILE_THE_TEST_RAN.clear();
    LogManager
        .getLogManager()
        .getLogger("")
        .addHandler(COLLECTING_WHAT_IS_LOGGED);

    try {

      persistence.store("4715");
      final var heldDelivery = new Thread(
          () -> dummyAdapter.invokeTask(MODULE, PROCESS, delivery("concurrentTask", "4715", "job-6")));
      heldDelivery.start();
      assertTrue(
          firstHandlerIsRunning.await(30, TimeUnit.SECONDS),
          "the first delivery never reached the handler");

      dummyAdapter.invokeTask(MODULE, PROCESS, delivery("concurrentTask", "4715", "job-6"));
      secondDeliveryCommitted.countDown();
      heldDelivery.join(TimeUnit.SECONDS.toMillis(30));

      assertEquals(2, handlerRuns.get(), "both deliveries have to run the handler");
      assertEquals(1, recordCount("4715"), "one delivery key, one record");

    } finally {
      DeliveryWorkflowService.WHILE_THE_CONCURRENT_TASK_RUNS.set(() -> {
      });
      LogManager
          .getLogManager()
          .getLogger("")
          .removeHandler(COLLECTING_WHAT_IS_LOGGED);
    }

    final var warning = LOGGED_WHILE_THE_TEST_RAN
        .stream()
        .filter(line -> line.contains("were processed at the SAME time"))
        .findFirst();
    assertTrue(
        warning.isPresent(),
        () -> "the overlap has to be said out loud: "
            + LOGGED_WHILE_THE_TEST_RAN);
    assertTrue(
        warning
            .get()
            .contains("demo1|test-module|DeliveryProcess|CREATED|job-6"),
        "the WARN names the delivery key, the adapter and the workflow");

    assertEquals(
        1.0,
        meterRegistry
            .get(VanillaBpMetrics.TASK_REDELIVERIES_CONCURRENT)
            .tag(VanillaBpMetrics.TAG_TASK_DEFINITION, "concurrentTask")
            .counter()
            .count(),
        "the delivery which found the key taken is what this meter counts");

  }

}
