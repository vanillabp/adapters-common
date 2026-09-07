package io.vanillabp.integration.test.extension;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;

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

}
