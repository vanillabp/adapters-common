package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.deployment.CallingWorkflowService;
import io.vanillabp.integration.test.deployment.ClaimedProcessMissingHandlerWiringSource;
import io.vanillabp.integration.test.deployment.TaskAggregate;
import io.vanillabp.integration.test.deployment.TaskAggregatePersistence;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The other half of {@code UnclaimedBpmnProcessTest}: in the same file with two
 * executable processes, the process a workflow service DOES claim has a task no
 * <code>&#64;WorkflowTask</code> method serves. That is the case which really is a
 * defect, and the process nobody claims next to it changes nothing about it - the boot
 * ends with the message it always had.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ClaimedProcessMissingHandlerTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("unclaimed-process/application.yaml", "application.yaml")
          .addClass(TaskAggregate.class)
          .addClass(TaskAggregatePersistence.class)
          .addClass(CallingWorkflowService.class)
          .addClass(ClaimedProcessMissingHandlerWiringSource.class)
          .addAsResource("bpmn/calling-and-called.bpmn", "processes/unclaimed/CallingAndCalled.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> {
        var current = throwable;
        while (current != null) {
          if ((current.getMessage() != null) && current.getMessage()
              .contains("Task wiring of BPMN process 'Calling'")) {
            assertTrue(current.getMessage().contains("'Activity_Unknown'"));
            assertTrue(current.getMessage().contains("@WorkflowTask(taskDefinition = \"notImplemented\")"));
            assertTrue(current.getMessage().contains(CallingWorkflowService.class.getName()));
            return;
          }
          current = current.getCause();
        }
        fail("expected the guiding wiring message of the claimed process but got: "
            + throwable);
      });

  @Test
  @DisplayName("A task of the claimed process without a method still ends the boot")
  public void aClaimedProcessMissingAHandlerStillFails() {
    // the assertion happens on the startup exception (assertException above)
  }

}
