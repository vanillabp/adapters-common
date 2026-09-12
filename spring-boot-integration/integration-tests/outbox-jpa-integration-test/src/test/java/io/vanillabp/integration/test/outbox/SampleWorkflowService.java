package io.vanillabp.integration.test.outbox;

import org.springframework.stereotype.Service;

import io.vanillabp.extension.sample.SampleNote;
import io.vanillabp.extension.sample.SampleNoteDetails;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.WorkflowService;

@Service
@WorkflowService(workflowAggregateClass = Aggregate.class)
public class SampleWorkflowService {

  /**
   * The BPMN element the reporting handler below serves.
   */
  public static final String REPORTED_ELEMENT = "Activity_Reported";

  /**
   * What that handler writes into the workflow aggregate.
   */
  public static final String REPORTED_BY_THE_HANDLER = "reported-by-the-handler";

  private final ProcessService<Aggregate> processService;

  public SampleWorkflowService(
      final ProcessService<Aggregate> processService) {

    this.processService = processService;

  }

  /**
   * The shape of a provider which notes down what it reported: it writes into the workflow
   * aggregate while it answers, and VanillaBP saves the aggregate afterwards.
   *
   * @param aggregate The workflow aggregate VanillaBP loaded
   * @param prefilled The note the extension prefilled
   * @return The note the extension publishes
   */
  @SampleNote(element = REPORTED_ELEMENT)
  public SampleNoteDetails noteWrittenWhileReporting(
      final Aggregate aggregate,
      final SampleNoteDetails prefilled) {

    aggregate.setReported(REPORTED_BY_THE_HANDLER);
    return prefilled;

  }

}
