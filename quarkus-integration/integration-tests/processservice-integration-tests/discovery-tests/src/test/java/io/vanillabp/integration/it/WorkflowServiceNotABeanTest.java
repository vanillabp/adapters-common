package io.vanillabp.integration.it;

import static io.vanillabp.integration.test.utils.TestCoverageUtils.testCoverageJavaAgent;
import static io.vanillabp.integration.test.utils.TestJvmArgs.quarkusProdModeTestDefaults;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusProdModeTest;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.NoBeanWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.WorkflowService;

@ExtendWith(SuppressOutputExtension.class)
public class WorkflowServiceNotABeanTest {

  @RegisterExtension
  static final QuarkusProdModeTest prodModeTest = new QuarkusProdModeTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(AggregatePersistence.class)
          .addClass(NoBeanWorkflowService.class)
          .addClass(Aggregate.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      // JVM args needed for tracking coverage. Check pom.xml for <systemPropertyVariables> tag
      .setJVMArgs(testCoverageJavaAgent(quarkusProdModeTestDefaults()))
      .assertBuildException(exception -> {
        final var rootCause = org.junit.platform.commons.util.ExceptionUtils
            .findNestedThrowables(exception)
            .getLast();
        assertInstanceOf(IllegalStateException.class, rootCause);
        assertEquals(
            """
                Class
                  %s
                was found by the VanillaBP extension as a
                  Workflow service annotated with @%s
                but the class itself is not a CDI bean.
                Please annotate it with a bean-defining annotation such as @ApplicationScoped."""
                .formatted(NoBeanWorkflowService.class.getName(), WorkflowService.class.getName()),
            rootCause.getMessage());
      });

  @Test
  public void testBuildFailedWithExpectedError() {
    fail("Should have failed at build time");
  }

}
