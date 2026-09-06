package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.persistence.MongoActiveRecordAggregate;
import io.vanillabp.integration.test.persistence.MongoActiveRecordWorkflowService;
import io.vanillabp.integration.test.persistence.SingleTaskWiringSource;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * An aggregate written as a MongoDB Panache active record (no repository
 * anywhere) is persisted by VanillaBP through Panache's operations, not through the
 * static methods Panache generates onto the entity.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoActiveRecordPersistenceTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(MongoActiveRecordAggregate.class)
          .addClass(MongoActiveRecordWorkflowService.class)
          .addClass(SingleTaskWiringSource.class)
          .addAsResource(new StringAsset("not parsed by the dummy adapter"), "processes/dummy/TestProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("quarkus.mongodb.database", "mongo-active-record-it");

  @Inject
  MongoActiveRecordWorkflowService workflowService;

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
  @DisplayName("A MongoDB Panache active record is enough: the workflow starts and its task updates the aggregate")
  public void mongoActiveRecordPersistsTheAggregate() {

    final var started = workflowService.startWorkflow("mongo-active-record");
    assertNotNull(started);

    final MongoActiveRecordAggregate stored = MongoActiveRecordAggregate.findById("mongo-active-record");
    assertNotNull(stored, "the aggregate was not stored by the default persistence");
    assertEquals("started", stored.status);

    final var outcome = dummyAdapter()
        .invokeTask("test-module", "TestProcess", context("processTask", "mongo-active-record"));
    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, outcome.kind());

    final MongoActiveRecordAggregate afterTask = MongoActiveRecordAggregate.findById("mongo-active-record");
    assertEquals("processed", afterTask.status, "the aggregate was loaded and saved around the task");

  }

}
