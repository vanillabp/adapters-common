package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.persistence.ActiveRecordAggregate;
import io.vanillabp.integration.test.persistence.ActiveRecordWorkflowService;
import io.vanillabp.integration.test.persistence.SingleTaskWiringSource;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * An aggregate written as a Hibernate ORM Panache active record (no
 * repository anywhere) is persisted by VanillaBP through the entity manager of the
 * aggregate's persistence unit. Both directions are exercised: starting a workflow
 * stores the aggregate, and a task delivered by the BPMS loads it, runs the handler
 * and stores the change.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PanacheActiveRecordPersistenceTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(ActiveRecordAggregate.class)
          .addClass(ActiveRecordWorkflowService.class)
          .addClass(SingleTaskWiringSource.class)
          .addAsResource(new StringAsset("not parsed by the dummy adapter"), "processes/dummy/TestProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  ActiveRecordWorkflowService workflowService;

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
  @DisplayName("A Panache active record is enough: the workflow starts and its task updates the aggregate")
  public void panacheActiveRecordPersistsTheAggregate() {

    final var started = QuarkusTransaction
        .requiringNew()
        .call(() -> workflowService.startWorkflow("active-record"));
    assertNotNull(started);

    final ActiveRecordAggregate stored = QuarkusTransaction
        .requiringNew()
        .call(() -> ActiveRecordAggregate.findById("active-record"));
    assertNotNull(stored, "the aggregate was not stored by the default persistence");
    assertEquals("started", stored.status);

    final var outcome = dummyAdapter()
        .invokeTask("test-module", "TestProcess", context("processTask", "active-record"));
    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, outcome.kind());

    final ActiveRecordAggregate afterTask = QuarkusTransaction
        .requiringNew()
        .call(() -> ActiveRecordAggregate.findById("active-record"));
    assertEquals("processed", afterTask.status, "the aggregate was loaded and saved around the task");

  }

}
