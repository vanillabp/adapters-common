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
import io.vanillabp.integration.test.persistence.SingleTaskWiringSource;
import io.vanillabp.integration.test.persistence.SpringDataAggregate;
import io.vanillabp.integration.test.persistence.SpringDataAggregateRepository;
import io.vanillabp.integration.test.persistence.SpringDataWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * An application using Spring Data repositories on Quarkus (extension
 * quarkus-spring-data-jpa) writes no {@code AggregatePersistenceAware} at all -
 * VanillaBP uses the repository, the same API the Spring Boot integration uses. Both directions are exercised: starting a workflow stores the
 * aggregate, and a task delivered by the BPMS loads it, runs the handler and stores
 * the change.
 */
@ExtendWith(SuppressOutputExtension.class)
public class SpringDataPersistenceTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(SpringDataAggregate.class)
          .addClass(SpringDataAggregateRepository.class)
          .addClass(SpringDataWorkflowService.class)
          .addClass(SingleTaskWiringSource.class)
          .addAsResource(new StringAsset("not parsed by the dummy adapter"), "processes/dummy/TestProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  SpringDataWorkflowService workflowService;

  @Inject
  SpringDataAggregateRepository repository;

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
  @DisplayName("A Spring Data repository is enough: the workflow starts and its task updates the aggregate")
  public void springDataRepositoryPersistsTheAggregate() {

    final var started = QuarkusTransaction
        .requiringNew()
        .call(() -> workflowService.startWorkflow("spring-data"));
    assertNotNull(started);

    final var stored = QuarkusTransaction
        .requiringNew()
        .call(() -> repository.findById("spring-data").orElse(null));
    assertNotNull(stored, "the aggregate was not stored by the default persistence");
    assertEquals("started", stored.getStatus());

    final var outcome = dummyAdapter()
        .invokeTask("test-module", "TestProcess", context("processTask", "spring-data"));
    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, outcome.kind());

    final var afterTask = QuarkusTransaction
        .requiringNew()
        .call(() -> repository.findById("spring-data").orElse(null));
    assertEquals("processed", afterTask.getStatus(), "the aggregate was loaded and saved around the task");

  }

}
