package io.vanillabp.integration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.adapter.DummyAdapters;
import io.vanillabp.integration.test.samples.ambiguoussubclasses.AmbiguousAggregate;
import io.vanillabp.integration.test.samples.ambiguoussubclasses.LoanApproval;
import io.vanillabp.integration.test.samples.ambiguoussubclasses.RiskAssessment;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Two subclasses of a base which names no BPMN process name their processes after
 * themselves, and one workflow aggregate has one {@code ProcessService} - so which of the two
 * processes {@code startWorkflow} starts cannot be decided. The build says so, naming both
 * classes, both processes and the attribute which settles it.
 * <p>
 * This used to be silent, because only the base was a workflow service and the two subclasses
 * were nothing but beans of it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class SubclassesWithoutASharedProcessTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addPackage(AmbiguousAggregate.class.getPackageName())
          .addClass(io.vanillabp.integration.test.adapter.DummyAdapters.class)
          .addClass(io.vanillabp.integration.test.adapter.TestPhaseTwoOutbox.class)
          .addClass(io.vanillabp.integration.test.adapter.TestAdapterDeploymentService.class)
          .addClass(io.vanillabp.integration.test.adapter.TestAdapterDeploymentServiceProducer.class)
          .addClass(io.vanillabp.integration.test.adapter.TestMigratableProcessService.class)
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE))
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter())
      .assertException(throwable -> {
        var current = throwable;
        while (current != null) {
          if ((current.getMessage() != null) && current.getMessage().contains("declare a DIFFERENT BPMN process")) {
            final var message = current.getMessage();
            assertTrue(message.contains(LoanApproval.class.getName()), message);
            assertTrue(message.contains(RiskAssessment.class.getName()), message);
            assertTrue(message.contains("'LoanApproval'"), message);
            assertTrue(message.contains("'RiskAssessment'"), message);
            assertTrue(message.contains("several subclasses of one annotated base"), message);
            return;
          }
          current = current.getCause();
        }
        fail("expected the build to report the two processes of the two subclasses but got: "
            + throwable);
      });

  @Test
  @DisplayName("Two subclasses naming two processes for one aggregate end the build")
  public void twoSubclassesWithoutASharedProcessEndTheBuild() {
    // the assertion happens on the build exception (assertException above)
  }

}
