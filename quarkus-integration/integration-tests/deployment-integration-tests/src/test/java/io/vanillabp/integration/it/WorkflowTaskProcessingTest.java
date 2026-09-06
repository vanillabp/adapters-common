package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.deployment.RequestScopedProbe;
import io.vanillabp.integration.test.deployment.TaskAggregate;
import io.vanillabp.integration.test.deployment.TaskAggregatePersistence;
import io.vanillabp.integration.test.deployment.TaskProcessWiringSource;
import io.vanillabp.integration.test.deployment.TaskWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Acceptance test of <code>&#64;WorkflowTask</code> processing on Quarkus with the dummy
 * adapter standing in for a BPMS: the adapter triggers task
 * invocations through the core's {@code WorkflowTaskInvoker}, which loads the
 * aggregate, runs the handler within a JTA transaction WITH an activated CDI
 * request context (proven by a {@code @RequestScoped} probe - handlers run on
 * adapter threads) and maps the three outcomes of the restored V1 contract.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowTaskProcessingTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("task-processing/application.yaml", "application.yaml")
          .addClass(TaskAggregate.class)
          .addClass(TaskAggregatePersistence.class)
          .addClass(TaskWorkflowService.class)
          .addClass(RequestScopedProbe.class)
          .addClass(TaskProcessWiringSource.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/TaskProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  TaskAggregatePersistence persistence;

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
        .filter(service -> "demo1".equals(service.getAdapterId()))
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

    };

  }

  @Test
  @DisplayName("All three outcomes, bindings, JTA transaction and request context work end-to-end")
  public void taskProcessingCoversAllOutcomesAndBindings() {

    final var dummyAdapter = dummyAdapter();

    // (a) normal return: task completed, aggregate changes saved; the handler
    // itself asserts an active JTA transaction and accesses a @RequestScoped bean
    persistence.seed("4711");
    final var completed = dummyAdapter.invokeTask("test-module", "TaskProcess", context("processTask", "4711"));
    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, completed.kind());
    assertEquals("processed", persistence.stored("4711").getStatus());
    assertEquals("request-scope-active", persistence.stored("4711").getRequestScopedProbe());

    // (b) TaskException: BPMN error outcome, aggregate changes COMMITTED anyway
    persistence.seed("4712");
    final var bpmnError = dummyAdapter.invokeTask("test-module", "TaskProcess", context("raiseBpmnError", "4712"));
    assertEquals(WorkflowTaskOutcome.Kind.BPMN_ERROR, bpmnError.kind());
    assertEquals("PAYMENT_FAILED", bpmnError.errorCode());
    assertEquals("bpmn-error-raised", persistence.stored("4712").getStatus());

    // (c) any other exception: propagates, task NOT completed, aggregate changes
    // NOT saved (transaction rolled back)
    persistence.seed("4713");
    final var failure = assertThrows(
        IllegalStateException.class,
        () -> dummyAdapter.invokeTask("test-module", "TaskProcess", context("failTask", "4713")));
    assertEquals("something broke", failure.getMessage());
    assertEquals("new", persistence.stored("4713").getStatus());

    // asynchronous task (@TaskId): stays open, receives the BPMS task id
    persistence.seed("4714");
    final var pending = dummyAdapter.invokeTask("test-module", "TaskProcess", new TaskInvocationContext() {

      @Override
      public String getTaskDefinition() {
        return "asyncTask";
      }

      @Override
      public String getWorkflowAggregateId() {
        return "4714";
      }

      @Override
      public String getTaskId() {
        return "task-0815";
      }

    });
    assertEquals(WorkflowTaskOutcome.Kind.COMPLETION_PENDING, pending.kind());
    assertEquals("task-0815", persistence.stored("4714").getTaskId());
    assertNull(persistence.stored("4711").getTaskId());

    // parameter binding: @TaskParam and @MultiInstance* values from the context
    persistence.seed("4715");
    final var multiInstances = new LinkedHashMap<String, MultiInstanceValue>();
    multiInstances.put("items", new MultiInstanceValue("item-3", 3, 7));
    dummyAdapter.invokeTask("test-module", "TaskProcess", new TaskInvocationContext() {

      @Override
      public String getTaskDefinition() {
        return "bindParameters";
      }

      @Override
      public String getWorkflowAggregateId() {
        return "4715";
      }

      @Override
      public Object getTaskParameter(
          final String name) {
        return "approval".equals(name)
            ? "APPROVED"
            : null;
      }

      @Override
      public Map<String, MultiInstanceValue> getMultiInstances() {
        return multiInstances;
      }

    });
    final var bound = persistence.stored("4715");
    assertEquals("APPROVED", bound.getStatus());
    assertEquals(3, bound.getIndex());
    assertEquals(7, bound.getTotal());
    assertEquals("item-3", bound.getElement());

  }

  @Test
  @DisplayName("Task-level adapter configuration resolves most-specific-wins from real application config")
  public void taskLevelConfigurationResolves() {

    final var overlay = io.smallrye.config.SmallRyeConfig.class
        .cast(org.eclipse.microprofile.config.ConfigProvider.getConfig())
        .getConfigMapping(io.vanillabp.bpmsdouble.quarkus.DummyAdapterOverlayProperties.class);

    assertEquals(42, overlay.testFor("test-module", "TaskProcess", "processTask", "demo1"));
    assertEquals(1, overlay.testFor("test-module", "TaskProcess", "someOtherTask", "demo1"));
    assertEquals(1, overlay.testFor(null, null, null, "demo1"));

  }

}
