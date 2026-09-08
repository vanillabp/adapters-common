package io.vanillabp.integration.test.extension;

import java.util.Collection;
import java.util.List;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;

/**
 * The application of this scenario: two workflow aggregates, one of which uses the
 * sample extension, and the BPMS double as the engine behind both.
 */
@SpringBootApplication
public class TestApplication {

  /**
   * The id no workflow of this scenario ever has - what the election is asked about to
   * see it refuse.
   */
  public static final long UNKNOWN_AGGREGATE_ID = 4711L;

  /**
   * Lets the BPMS double report the workflows of this scenario as running, so the
   * election has something to elect. The one id above is deliberately unknown.
   *
   * @return The awareness the double reports
   */
  @Bean
  public DummyTaskAwarenessSource everyWorkflowRunsHere() {

    return (
        adapterId,
        workflowAggregateId,
        taskId) -> String.valueOf(UNKNOWN_AGGREGATE_ID).equals(String.valueOf(workflowAggregateId))
            ? WorkflowAwareness.UNKNOWN_TO_BPMS
            : WorkflowAwareness.ACTIVE;

  }

  /**
   * The element the wiring below reports a name for.
   */
  public static final String USER_TASK_ID = "Activity_Review";

  /**
   * What a modeller wrote on that element - the label an extension asks VanillaBP for
   * instead of parsing the same BPMN bytes a second time.
   */
  public static final String USER_TASK_NAME = "Review the note";

  /**
   * Stands in for what an adapter reads out of the models of this scenario: which BPMN
   * process each file declares, and the tasks of it. Without the first half the double
   * names a process after its file, and the processes the workflow services claim would
   * be wired by nobody.
   * <p>
   * The one task is a USER task, which needs no handler and therefore fits a scenario
   * whose workflow services carry methods of the extension and no
   * <code>&#64;WorkflowTask</code>. It carries the name a modeller wrote on it, which is
   * what {@code ExtensionHandlerTest#theExtensionReadsTheBpmnName} asks VanillaBP for.
   *
   * @return What this scenario's BPMN files hold
   */
  @Bean
  public DummyTaskWiringSource notedProcessModels() {

    return new DummyTaskWiringSource() {

      @Override
      public List<String> executableProcessesOf(
          final String adapterId,
          final String workflowModuleId,
          final String filename) {

        return switch (filename) {
          case "dummy-process.bpmn" -> List.of("DummyProcess");
          case "unnoted-process.bpmn" -> List.of("UnnotedProcess");
          default -> List.of();
        };

      }

      @Override
      public Collection<BpmnTaskSpec> tasksOf(
          final String adapterId,
          final String workflowModuleId,
          final String bpmnProcessId) {

        return "DummyProcess".equals(bpmnProcessId)
            ? List.of(BpmnTaskSpec.userTask(USER_TASK_ID, "reviewTheNote", USER_TASK_NAME))
            : List.of();

      }

    };

  }

}
