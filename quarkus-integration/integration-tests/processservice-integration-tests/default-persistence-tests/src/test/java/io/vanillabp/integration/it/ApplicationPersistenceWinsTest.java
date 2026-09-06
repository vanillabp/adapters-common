package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.vanillabp.integration.test.persistence.ApplicationRepositoryPersistence;
import io.vanillabp.integration.test.persistence.RepositoryAggregate;
import io.vanillabp.integration.test.persistence.RepositoryAggregateRepository;
import io.vanillabp.integration.test.persistence.RepositoryWorkflowService;
import io.vanillabp.integration.test.persistence.SingleTaskWiringSource;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * The defaults are a fallback, never a takeover. This application has a
 * Panache repository for its aggregate AND an {@code AggregatePersistenceAware} of
 * its own - the latter has to be the one VanillaBP calls, in both directions.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ApplicationPersistenceWinsTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(RepositoryAggregate.class)
          .addClass(RepositoryAggregateRepository.class)
          .addClass(ApplicationRepositoryPersistence.class)
          .addClass(RepositoryWorkflowService.class)
          .addClass(SingleTaskWiringSource.class)
          .addAsResource(new StringAsset("not parsed by the dummy adapter"), "processes/dummy/TestProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  RepositoryWorkflowService workflowService;

  @Inject
  RepositoryAggregateRepository repository;

  @Inject
  ApplicationRepositoryPersistence applicationPersistence;

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
  @DisplayName("An AggregatePersistenceAware of the application beats the Panache repository default")
  public void applicationPersistenceIsUsedAlthoughARepositoryExists() {

    final var started = QuarkusTransaction
        .requiringNew()
        .call(() -> workflowService.startWorkflow("application-owned"));
    assertNotNull(started);
    assertTrue(applicationPersistence.getSaves() > 0, "the application's save was not called");

    final var outcome = dummyAdapter()
        .invokeTask("test-module", "TestProcess", context("processTask", "application-owned"));
    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, outcome.kind());
    assertTrue(applicationPersistence.getLoads() > 0, "the application's loadById was not called");

    final var afterTask = QuarkusTransaction
        .requiringNew()
        .call(() -> repository.findById("application-owned"));
    assertEquals("processed", afterTask.getStatus());

  }

}
