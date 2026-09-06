package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.deployment.CallingAndCalledWiringSource;
import io.vanillabp.integration.test.deployment.OrphanMethodCallingWorkflowService;
import io.vanillabp.integration.test.deployment.TaskAggregate;
import io.vanillabp.integration.test.deployment.TaskAggregatePersistence;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The reverse direction of the wiring validation stays as loud as it was: a
 * <code>&#64;WorkflowTask</code> method matching no task of any BPMN process of its
 * workflow module ends the boot, and a process the deployment only warns about does not
 * make that check quieter - the unclaimed process is never validated against methods, so
 * it can neither excuse nor hide one.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OrphanMethodNextToUnclaimedProcessTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("unclaimed-process/application.yaml", "application.yaml")
          .addClass(TaskAggregate.class)
          .addClass(TaskAggregatePersistence.class)
          .addClass(OrphanMethodCallingWorkflowService.class)
          .addClass(CallingAndCalledWiringSource.class)
          .addAsResource("bpmn/calling-and-called.bpmn", "processes/unclaimed/CallingAndCalled.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> {
        var current = throwable;
        while (current != null) {
          if ((current.getMessage() != null) && current.getMessage().contains("activityNobodyModelled")) {
            assertTrue(current.getMessage().contains("fix the annotation"));
            return;
          }
          current = current.getCause();
        }
        fail("expected the orphan method to be named but got: "
            + throwable);
      });

  @Test
  @DisplayName("A method matching no task still ends the boot, unclaimed process or not")
  public void anOrphanMethodStillEndsTheBoot() {
    // the assertion happens on the startup exception (assertException above)
  }

}
