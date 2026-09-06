package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jboss.shrinkwrap.api.asset.StringAsset;
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
import io.vanillabp.integration.test.deployment.IterationResolver;
import io.vanillabp.integration.test.deployment.ResolverAggregate;
import io.vanillabp.integration.test.deployment.ResolverAggregatePersistence;
import io.vanillabp.integration.test.deployment.ResolverProcessWiringSource;
import io.vanillabp.integration.test.deployment.ResolverWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * The resolver named by
 * <code>&#64;MultiInstanceElement(resolverBean = ...)</code> is a bean nothing injects.
 * Quarkus removes such beans while building the application, so VanillaBP's lookup
 * found nothing and every iteration of the task failed - with a message asking the
 * developer to define the bean they had defined. The build step keeps it now, and the
 * application needs no Quarkus annotation for it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MultiInstanceResolverBeanTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("multi-instance-resolver/application.yaml", "application.yaml")
          .addClass(ResolverAggregate.class)
          .addClass(ResolverAggregatePersistence.class)
          .addClass(ResolverWorkflowService.class)
          .addClass(IterationResolver.class)
          .addClass(ResolverProcessWiringSource.class)
          .addAsResource(new StringAsset("not parsed by the dummy adapter"), "processes/dummy/ResolverProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  ResolverAggregatePersistence persistence;

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

  @Test
  @DisplayName("A resolver bean nothing injects survives the build and resolves the iteration")
  public void resolverBeanIsAvailableAtTaskTime() {

    persistence.seed("4711");
    final Map<String, MultiInstanceValue> multiInstances = new LinkedHashMap<>();
    multiInstances.put("items", new MultiInstanceValue("item-2", 2, 5));

    final var outcome = dummyAdapter().invokeTask("test-module", "ResolverProcess", new TaskInvocationContext() {

      @Override
      public String getTaskDefinition() {
        return "iterate";
      }

      @Override
      public String getWorkflowAggregateId() {
        return "4711";
      }

      @Override
      public Map<String, MultiInstanceValue> getMultiInstances() {
        return multiInstances;
      }

    });

    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, outcome.kind());
    assertEquals(
        "item-2@2/5",
        persistence.stored("4711").getResolved(),
        "the resolver bean was not found - Quarkus removed it while building the application");

  }

}
