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
import io.vanillabp.integration.test.MetaAnnotatedWorkflowService;
import io.vanillabp.integration.test.OurOwnWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The same defect in its second shape: an annotation of the application carrying
 * <code>&#64;WorkflowService</code>. Jandex resolves no meta-annotation, so it reports the
 * annotation type as the annotated one and the class using it is never seen - the build
 * used to end asking for a CDI bean of the annotation. It ends naming both types now, and
 * says where the annotation belongs.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowServiceViaAnAnnotationOfItsOwnTest {

  @RegisterExtension
  static final QuarkusProdModeTest prodModeTest = new QuarkusProdModeTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(AggregatePersistence.class)
          .addClass(OurOwnWorkflowService.class)
          .addClass(MetaAnnotatedWorkflowService.class)
          .addClass(Aggregate.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      // JVM args needed for tracking coverage. Check pom.xml for <systemPropertyVariables> tag
      .setJVMArgs(testCoverageJavaAgent(quarkusProdModeTestDefaults()))
      .assertBuildException(exception -> {
        final var reported = GuidingMessage.of(exception, "@WorkflowService sits on the annotation");
        assertTrue(
            reported.contains(OurOwnWorkflowService.class.getName()),
            "the annotation carrying @WorkflowService is not named: "
                + reported);
        assertTrue(
            reported.contains(MetaAnnotatedWorkflowService.class.getName()),
            "the class using it is not named: "
                + reported);
        assertTrue(
            reported.contains("Move @WorkflowService onto the class holding the @WorkflowTask methods"),
            "the way out is not named: "
                + reported);
      });

  @Test
  @DisplayName("@WorkflowService reached through an own annotation ends the build naming both")
  public void anAnnotationOfTheApplicationEndsTheBuild() {
    fail("Should have failed at build time");
  }

}
