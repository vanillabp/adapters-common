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
import io.vanillabp.integration.test.persistence.RepositoryAggregate;
import io.vanillabp.integration.test.persistence.RepositoryAggregateRepository;
import io.vanillabp.integration.test.persistence.RepositoryWorkflowService;
import io.vanillabp.integration.test.persistence.SingleTaskWiringSource;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * An application storing its aggregate in a Hibernate ORM Panache
 * repository writes no {@code AggregatePersistenceAware} at all - VanillaBP uses the
 * repository. Both directions are exercised: starting a workflow stores the
 * aggregate, and a task delivered by the BPMS loads it, runs the handler and stores
 * the change.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PanacheRepositoryPersistenceTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(RepositoryAggregate.class)
          .addClass(RepositoryAggregateRepository.class)
          .addClass(RepositoryWorkflowService.class)
          .addClass(SingleTaskWiringSource.class)
          .addAsResource(new StringAsset("not parsed by the dummy adapter"), "processes/dummy/TestProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  RepositoryWorkflowService workflowService;

  @Inject
  RepositoryAggregateRepository repository;

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
  @DisplayName("A Panache repository is enough: the workflow starts and its task updates the aggregate")
  public void panacheRepositoryPersistsTheAggregate() {

    final var started = QuarkusTransaction
        .requiringNew()
        .call(() -> workflowService.startWorkflow("panache-repository"));
    assertNotNull(started);

    final var stored = QuarkusTransaction
        .requiringNew()
        .call(() -> repository.findById("panache-repository"));
    assertNotNull(stored, "the aggregate was not stored by the default persistence");
    assertEquals("started", stored.getStatus());

    final var outcome = dummyAdapter()
        .invokeTask("test-module", "TestProcess", context("processTask", "panache-repository"));
    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, outcome.kind());

    final var afterTask = QuarkusTransaction
        .requiringNew()
        .call(() -> repository.findById("panache-repository"));
    assertEquals("processed", afterTask.getStatus(), "the aggregate was loaded and saved around the task");

  }

}
