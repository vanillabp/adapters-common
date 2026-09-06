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
import io.vanillabp.integration.test.persistence.MongoRepositoryAggregate;
import io.vanillabp.integration.test.persistence.MongoRepositoryAggregateRepository;
import io.vanillabp.integration.test.persistence.MongoRepositoryWorkflowService;
import io.vanillabp.integration.test.persistence.SingleTaskWiringSource;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * An application storing its aggregate in a MongoDB Panache repository
 * writes no {@code AggregatePersistenceAware} at all - VanillaBP uses the repository
 * (persistOrUpdate). The write takes part in the transaction VanillaBP opens, because
 * MongoDB Panache binds a MongoDB session to it (see {@code MongoOneTransactionTest}).
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoRepositoryPersistenceTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(MongoRepositoryAggregate.class)
          .addClass(MongoRepositoryAggregateRepository.class)
          .addClass(MongoRepositoryWorkflowService.class)
          .addClass(SingleTaskWiringSource.class)
          .addAsResource(new StringAsset("not parsed by the dummy adapter"), "processes/dummy/TestProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("quarkus.mongodb.database", "mongo-repository-it");

  @Inject
  MongoRepositoryWorkflowService workflowService;

  @Inject
  MongoRepositoryAggregateRepository repository;

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
  @DisplayName("A MongoDB Panache repository is enough: the workflow starts and its task updates the aggregate")
  public void mongoRepositoryPersistsTheAggregate() {

    final var started = workflowService.startWorkflow("mongo-repository");
    assertNotNull(started);

    final var stored = repository.findById("mongo-repository");
    assertNotNull(stored, "the aggregate was not stored by the default persistence");
    assertEquals("started", stored.getStatus());

    final var outcome = dummyAdapter()
        .invokeTask("test-module", "TestProcess", context("processTask", "mongo-repository"));
    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, outcome.kind());

    assertEquals(
        "processed",
        repository.findById("mongo-repository").getStatus(),
        "the aggregate was loaded and saved around the task");

  }

}
