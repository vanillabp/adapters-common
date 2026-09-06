package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.test.deployment.VersionedAggregate;
import io.vanillabp.integration.test.deployment.VersionedAggregatePersistence;
import io.vanillabp.integration.test.deployment.VersionedProcessVersionSource;
import io.vanillabp.integration.test.deployment.VersionedProcessWiringSource;
import io.vanillabp.integration.test.deployment.VersionedWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Acceptance test of <code>&#64;WorkflowTask(version = ...)</code> on Quarkus: the version
 * the adapter reports decides which method serves a delivered task,
 * ranges made of numbers are compared without asking the BPMS, and a range naming a
 * version TAG is resolved through the catalog the adapter registered - including the
 * query for a version this application never deployed itself.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ProcessVersionsTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("process-versions/application.yaml", "application.yaml")
          .addClass(VersionedAggregate.class)
          .addClass(VersionedAggregatePersistence.class)
          .addClass(VersionedWorkflowService.class)
          .addClass(VersionedProcessWiringSource.class)
          .addClass(VersionedProcessVersionSource.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/VersionedProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  VersionedAggregatePersistence persistence;

  @Inject
  VersionedProcessVersionSource versionSource;

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
      final String aggregateId,
      final String processVersion) {

    return new TaskInvocationContext() {

      @Override
      public String getTaskDefinition() {
        return "versionedTask";
      }

      @Override
      public String getWorkflowAggregateId() {
        return aggregateId;
      }

      @Override
      public String getProcessVersion() {
        return processVersion;
      }

    };

  }

  @Test
  @DisplayName("The process version decides which method serves the task, tags included")
  public void theProcessVersionDecidesWhichMethodServesTheTask() {

    final var dummyAdapter = dummyAdapter();

    // the version tag was resolved while the application booted, once
    assertEquals(1, versionSource.getQueries());

    persistence.seed("4711");
    dummyAdapter.invokeTask("test-module", "VersionedProcess", context("4711", "2"));
    assertEquals("upToTwo", persistence.stored("4711").getServedBy());

    dummyAdapter.invokeTask("test-module", "VersionedProcess", context("4711", "3"));
    assertEquals("three", persistence.stored("4711").getServedBy());

    // the version carrying the tag - and no further BPMS query for it
    dummyAdapter.invokeTask("test-module", "VersionedProcess", context("4711", "4"));
    assertEquals("tagged", persistence.stored("4711").getServedBy());
    assertEquals(1, versionSource.getQueries());

    // a BPMS reporting NO version reaches none of these methods: every one of them
    // names versions, and which of them applies is not something to guess
    final var unreported = assertThrows(
        IllegalStateException.class,
        () -> dummyAdapter.invokeTask("test-module", "VersionedProcess", context("4711", null)));
    assertTrue(unreported.getMessage().contains("reports no process version"), unreported.getMessage());
    assertEquals("tagged", persistence.stored("4711").getServedBy(), "no method ran");

    // ANOTHER cluster node deploys version 5 and moves the tag to it: this node has
    // never seen that version, so it asks the BPMS while the task is dispatched
    versionSource.deployedElsewhere("5", "release-2026");
    dummyAdapter.invokeTask("test-module", "VersionedProcess", context("4711", "5"));
    assertEquals("tagged", persistence.stored("4711").getServedBy());
    assertTrue(
        versionSource.getQueries() > 1,
        "the version deployed by another node was looked up on demand");

    // the version which LOST the tag is served by nobody, and the message names it
    final var unmatched = assertThrows(
        IllegalStateException.class,
        () -> dummyAdapter.invokeTask("test-module", "VersionedProcess", context("4711", "4")));
    assertTrue(unmatched.getMessage().contains("process version '4'"), unmatched.getMessage());

  }

}
