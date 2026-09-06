package io.vanillabp.integration.it;

import static io.vanillabp.integration.test.utils.TestCoverageUtils.testCoverageJavaAgent;
import static io.vanillabp.integration.test.utils.TestJvmArgs.quarkusProdModeTestDefaults;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusProdModeTest;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.WorkflowServiceImplementingAnInterface;
import io.vanillabp.integration.test.WorkflowServiceInterface;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An interface carrying <code>&#64;WorkflowService</code> ends the build. Jandex reports
 * the interface as the annotated type, so the interface used to become the workflow
 * service and the methods declared IN it used to serve the tasks - while Spring Boot reads
 * the implementing class, whose overriding methods carry no annotation at all. The message
 * names the interface and the class which brought it in, and says where the annotation
 * belongs.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowServiceOnAnInterfaceTest {

  @RegisterExtension
  static final QuarkusProdModeTest prodModeTest = new QuarkusProdModeTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowServiceInterface.class)
          .addClass(WorkflowServiceImplementingAnInterface.class)
          .addClass(Aggregate.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      // JVM args needed for tracking coverage. Check pom.xml for <systemPropertyVariables> tag
      .setJVMArgs(testCoverageJavaAgent(quarkusProdModeTestDefaults()))
      .assertBuildException(exception -> {
        final var reported = GuidingMessage.of(exception, "@WorkflowService sits on the interface");
        assertTrue(
            reported.contains(WorkflowServiceInterface.class.getName()),
            "the interface carrying the annotation is not named: "
                + reported);
        assertTrue(
            reported.contains(WorkflowServiceImplementingAnInterface.class.getName()),
            "the class which brought the annotation in is not named: "
                + reported);
        assertTrue(
            reported.contains("Move @WorkflowService onto the class holding the @WorkflowTask methods"),
            "the way out is not named: "
                + reported);
      });

  @Test
  @DisplayName("@WorkflowService on an interface ends the build naming interface and class")
  public void anAnnotatedInterfaceEndsTheBuild() {
    fail("Should have failed at build time");
  }

}
