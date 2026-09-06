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
import io.vanillabp.integration.test.deployment.ClassVersionedAggregate;
import io.vanillabp.integration.test.deployment.ClassVersionedAggregatePersistence;
import io.vanillabp.integration.test.deployment.ClassVersionedProcessVersionSource;
import io.vanillabp.integration.test.deployment.ClassVersionedProcessWiringSource;
import io.vanillabp.integration.test.deployment.LoanApprovalAfterTwo;
import io.vanillabp.integration.test.deployment.LoanApprovalUpToTwo;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Acceptance test of <code>&#64;BpmnProcess(version = ...)</code> on Quarkus: two
 * workflow service classes serve one BPMN task of one process, each bound to a
 * generation of the model by the range its <code>&#64;BpmnProcess</code> declares, and
 * not one method repeats that range. The version the adapter reports decides which of
 * the two classes runs.
 * <p>
 * A method which INHERITS a range is as restricted as one naming it, so a delivery
 * whose version the BPMS did not report reaches neither class, and the message names
 * the declaration the range came from.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ClassLevelProcessVersionsTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("class-versions/application.yaml", "application.yaml")
          .addClass(ClassVersionedAggregate.class)
          .addClass(ClassVersionedAggregatePersistence.class)
          .addClass(LoanApprovalUpToTwo.class)
          .addClass(LoanApprovalAfterTwo.class)
          .addClass(ClassVersionedProcessWiringSource.class)
          .addClass(ClassVersionedProcessVersionSource.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/ClassVersionedProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  ClassVersionedAggregatePersistence persistence;

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
  @DisplayName("The deployed version picks the workflow service class, no method naming a version")
  public void theProcessVersionDecidesWhichClassServesTheTask() {

    final var dummyAdapter = dummyAdapter();
    persistence.seed("4711");

    dummyAdapter.invokeTask("test-module", "ClassVersionedProcess", context("4711", "2"));
    assertEquals("upToTwo", persistence.stored("4711").getServedBy());

    dummyAdapter.invokeTask("test-module", "ClassVersionedProcess", context("4711", "3"));
    assertEquals("afterTwo", persistence.stored("4711").getServedBy());

    // an inherited range restricts as much as one written on the method, so a BPMS
    // reporting no version reaches neither class - and the message names the
    // declaration the range came from, since neither method carries one
    final var unreported = assertThrows(
        IllegalStateException.class,
        () -> dummyAdapter.invokeTask("test-module", "ClassVersionedProcess", context("4711", null)));
    assertTrue(
        unreported.getMessage().contains("inherits its range from the @BpmnProcess"),
        unreported.getMessage());
    assertEquals("afterTwo", persistence.stored("4711").getServedBy(), "no method ran");

  }

}
