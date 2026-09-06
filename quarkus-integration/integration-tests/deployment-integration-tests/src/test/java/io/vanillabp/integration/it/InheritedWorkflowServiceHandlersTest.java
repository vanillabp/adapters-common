package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.adapter.dummy.runtime.DummyDeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.inheritance.HandlersEveryWorkflowServiceInherits;
import io.vanillabp.integration.test.inheritance.InheritedAggregate;
import io.vanillabp.integration.test.inheritance.InheritedAggregatePersistence;
import io.vanillabp.integration.test.inheritance.InheritedProcessWiringSource;
import io.vanillabp.integration.test.inheritance.InheritingWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * The class inheriting <code>&#64;WorkflowService</code> serves the BPMN process named after
 * ITSELF, and the handlers of that process are the ones it writes plus the ones it inherits.
 * The BPMN file is named after the subclass, which is what the developer would draw after
 * reading their own class, and both tasks of it run through the core.
 * <p>
 * Quarkus used to register the superclass here: the file would have belonged to no workflow
 * service, and the handler written in the subclass would have been invisible, because the core
 * scans the class it was handed.
 */
@ExtendWith(SuppressOutputExtension.class)
public class InheritedWorkflowServiceHandlersTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("inheritance/application.yaml", "application.yaml")
          .addClass(InheritedAggregate.class)
          .addClass(InheritedAggregatePersistence.class)
          .addClass(HandlersEveryWorkflowServiceInherits.class)
          .addClass(InheritingWorkflowService.class)
          .addClass(InheritedProcessWiringSource.class)
          .addAsResource(
              new StringAsset("not parsed by the dummy adapter"),
              "processes/dummy/InheritingWorkflowService.bpmn")
          .addAsResource("inheritance-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  InheritedAggregatePersistence persistence;

  @Inject
  ProcessService<InheritedAggregate> processService;

  @Inject
  @Any
  Instance<List<AdapterDeploymentService<Object, Object>>> deploymentServices;

  private DummyDeploymentService dummyAdapter() {

    return deploymentServices
        .stream()
        .filter(java.util.Objects::nonNull)
        .flatMap(List::stream)
        .filter(DummyDeploymentService.class::isInstance)
        .map(DummyDeploymentService.class::cast)
        .findFirst()
        .orElseThrow();

  }

  private WorkflowTaskOutcome invoke(
      final String taskDefinition) {

    return dummyAdapter()
        .invokeTask("inheritance-module", "InheritingWorkflowService", new TaskInvocationContext() {

          @Override
          public String getTaskDefinition() {
            return taskDefinition;
          }

          @Override
          public String getWorkflowAggregateId() {
            return "4711";
          }

        });

  }

  @Test
  @DisplayName("The handler of the subclass and the handler it inherits both serve the process")
  public void bothHandlersOfTheInheritingClassAreWired() {

    assertEquals(
        "inheritance-module",
        processService.getWorkflowModuleId());

    persistence.seed("4711");
    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, invoke("ownTask").kind());
    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, invoke("inheritedTask").kind());
    assertEquals(
        "nobody+ownTask+inheritedTask",
        persistence
            .stored("4711")
            .getServedBy());

  }

}
