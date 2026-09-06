package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.integration.adapter.migration.observability.DeliveryMdc;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.health.AdapterHealth;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.runtime.health.VanillaBpReadinessCheck;
import io.vanillabp.integration.test.deployment.TaskAggregate;
import io.vanillabp.integration.test.deployment.TaskAggregatePersistence;
import io.vanillabp.integration.test.deployment.TaskProcessWiringSource;
import io.vanillabp.integration.test.deployment.TaskWorkflowService;
import io.vanillabp.integration.test.deployment.TestHealthSource;
import io.vanillabp.integration.test.deployment.TestMeterRegistryProducer;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Acceptance test of what an operator gets to see on Quarkus: every task
 * delivery is counted by outcome and measured, it carries a logging context naming
 * the workflow it belongs to, and the BPMS adapters contribute what they know about
 * their BPMS to the readiness check.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ObservabilityTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TaskProcess";

  private static final String ADAPTER = "demo1";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("observability/application.yaml", "application.yaml")
          .addClass(TaskAggregate.class)
          .addClass(TaskAggregatePersistence.class)
          .addClass(TaskWorkflowService.class)
          .addClass(io.vanillabp.integration.test.deployment.RequestScopedProbe.class)
          .addClass(TaskProcessWiringSource.class)
          .addClass(TestMeterRegistryProducer.class)
          .addClass(TestHealthSource.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/TaskProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  TaskAggregatePersistence persistence;

  @Inject
  SimpleMeterRegistry meterRegistry;

  @Inject
  @org.eclipse.microprofile.health.Readiness
  VanillaBpReadinessCheck readinessCheck;

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

  private TaskInvocationContext context(
      final String taskDefinition,
      final String aggregateId) {

    return new TaskInvocationContext() {

      @Override
      public String getTaskDefinition() {
        return taskDefinition;
      }

      @Override
      public String getWorkflowAggregateId() {
        return aggregateId;
      }

      @Override
      public String getAdapterId() {
        return ADAPTER;
      }

      @Override
      public String getDeliveryId() {
        return "job-"
            + aggregateId;
      }

    };

  }

  @Test
  @DisplayName("Every delivery is counted by its outcome, measured, and carries its workflow in the log context")
  public void deliveriesAreCountedMeasuredAndLogged() {

    final var dummyAdapter = dummyAdapter();

    persistence.seed("q-1");
    dummyAdapter.invokeTask(MODULE, PROCESS, context("processTask", "q-1"));
    persistence.seed("q-2");
    dummyAdapter.invokeTask(MODULE, PROCESS, context("raiseBpmnError", "q-2"));
    persistence.seed("q-3");
    assertThrows(
        RuntimeException.class,
        () -> dummyAdapter.invokeTask(MODULE, PROCESS, context("failTask", "q-3")));

    assertEquals(1.0, deliveries("processTask", "completed"));
    assertEquals(1.0, deliveries("raiseBpmnError", "bpmn-error"));
    assertEquals(1.0, deliveries("failTask", "failed"));

    final var timer = meterRegistry
        .get(VanillaBpMetrics.TASK_DELIVERY_DURATION)
        .tag(VanillaBpMetrics.TAG_TASK_DEFINITION, "processTask")
        .timer();
    assertEquals(1L, timer.count());
    assertTrue(timer.totalTime(TimeUnit.NANOSECONDS) > 0);

    persistence.seed("q-4");
    dummyAdapter.invokeTask(MODULE, PROCESS, context("recordMdc", "q-4"));

    final var during = TaskWorkflowService.MDC_DURING_TASK;
    assertEquals(ADAPTER, during.get(DeliveryMdc.ADAPTER));
    assertEquals(MODULE, during.get(DeliveryMdc.WORKFLOW_MODULE));
    assertEquals(PROCESS, during.get(DeliveryMdc.BPMN_PROCESS));
    assertEquals("q-4", during.get(DeliveryMdc.WORKFLOW_AGGREGATE_ID));
    assertEquals("recordMdc", during.get(DeliveryMdc.TASK_DEFINITION));
    assertEquals("job-q-4", during.get(DeliveryMdc.DELIVERY_ID));

    DeliveryMdc.KEYS
        .forEach(key -> assertNull(
            org.slf4j.MDC.get(key),
            "the key '%s' has to be gone once the delivery is done".formatted(key)));

  }

  private double deliveries(
      final String taskDefinition,
      final String outcome) {

    return meterRegistry
        .get(VanillaBpMetrics.TASK_DELIVERIES)
        .tag(VanillaBpMetrics.TAG_ADAPTER, ADAPTER)
        .tag(VanillaBpMetrics.TAG_WORKFLOW_MODULE, MODULE)
        .tag(VanillaBpMetrics.TAG_BPMN_PROCESS, PROCESS)
        .tag(VanillaBpMetrics.TAG_TASK_DEFINITION, taskDefinition)
        .tag(VanillaBpMetrics.TAG_OUTCOME, outcome)
        .counter()
        .count();

  }

  @Test
  @DisplayName("An unconfigured adapter is not an outage, an unreachable one is, and both name the adapter")
  public void readinessReportsWhatTheAdaptersFound() {

    try {

      TestHealthSource.answer = adapterId -> null;
      final var nothing = readinessCheck.call();
      assertEquals(HealthCheckResponse.Status.UP, nothing.getStatus());
      assertTrue(
          nothing
              .getData()
              .isEmpty() || nothing
                  .getData()
                  .get()
                  .isEmpty(),
          "an adapter which checks nothing is absent rather than reported as healthy");

      TestHealthSource.answer = adapterId -> AdapterHealth
          .unknown(
              adapterId,
              "dummy",
              "The adapter is not configured yet",
              AdapterHealth
                  .detailsBuilder()
                  .with("address", "<none>")
                  .build());
      final var unconfigured = readinessCheck.call();
      assertEquals(
          HealthCheckResponse.Status.UP,
          unconfigured.getStatus(),
          "an application which booted with a guiding warning is not an outage");
      assertEquals(
          "UNKNOWN",
          unconfigured
              .getData()
              .orElseThrow()
              .get(ADAPTER
                  + ".status"));

      TestHealthSource.answer = adapterId -> AdapterHealth
          .down(
              adapterId,
              "dummy",
              "Connection refused",
              AdapterHealth
                  .detailsBuilder()
                  .with("address", "http://localhost:26500")
                  .build());
      final var unreachable = readinessCheck.call();
      assertEquals(HealthCheckResponse.Status.DOWN, unreachable.getStatus());
      final var details = unreachable
          .getData()
          .orElseThrow();
      assertEquals("Connection refused", details.get(ADAPTER
          + ".description"));
      assertEquals(
          "http://localhost:26500",
          details.get(ADAPTER
              + ".address"),
          "the address is what lets an operator act without reading the configuration");

      TestHealthSource.answer = adapterId -> {
        throw new IllegalStateException("the check itself is broken");
      };
      final var broken = readinessCheck.call();
      assertEquals(HealthCheckResponse.Status.DOWN, broken.getStatus());
      assertFalse(
          broken
              .getData()
              .orElseThrow()
              .get(ADAPTER
                  + ".description")
              .toString()
              .isBlank());

    } finally {
      TestHealthSource.answer = adapterId -> null;
    }

  }

}
