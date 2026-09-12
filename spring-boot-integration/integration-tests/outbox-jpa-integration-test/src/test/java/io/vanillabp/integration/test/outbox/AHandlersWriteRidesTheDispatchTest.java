package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * What a handler wrote while an extension reported, when the report failed right behind it.
 * <p>
 * This is the whole point of one unit of work. gruelbox dispatches inside a transaction of
 * its own and ticks the entry off in it, so a dispatch which throws is repeated. The
 * handler takes part in that transaction, which means the aggregate it changed goes back
 * with the entry and the repetition starts from the state the first attempt started from.
 * Two transactions would have left the change of the failed attempt in the database, and
 * the next attempt would have reported on top of its own half-finished work.
 */
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class AHandlersWriteRidesTheDispatchTest {

  /**
   * The BPMN process the workflow service serves. It names none, so the convention applies
   * and the class name is the process id.
   */
  private static final String PROCESS = SampleWorkflowService.class.getSimpleName();

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private PhaseTwoOutbox outbox;

  @Autowired
  private SampleExtension extension;

  @Autowired
  private AggregateRepository aggregates;

  @BeforeEach
  public void resetExtension() {

    extension.reset();

  }

  private Aggregate startWorkflowAndSchedule(
      final String content,
      final String event) {

    return transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent(content);
      final var attached = processService.startWorkflow(aggregate);
      outbox
          .schedule(
              SampleExtension.call("test-module", PROCESS, attached.getId().toString(), event));
      return attached;
    });

  }

  @Test
  @DisplayName("A report which fails behind the handler takes the handler's write with it")
  public void aFailedReportRollsTheHandlersWriteBack() throws Exception {

    extension.reportAndFailNextDispatches(1);

    final var aggregate = startWorkflowAndSchedule("reported-once", "created");
    assertNotNull(aggregate);

    // the first attempt ran the handler and threw, so waiting for a dispatch waits for
    // the attempt behind the failed one
    extension.awaitDispatched(1, 10000);
    assertEquals(2, extension.getAttempts(), "the attempt which was to fail never ran");

    assertNull(
        aggregates
            .findById(aggregate.getId())
            .orElseThrow()
            .getReported(),
        "what the handler wrote committed although the entry was repeated");

  }

  @Test
  @DisplayName("A report which succeeds keeps what the handler wrote")
  public void aSucceedingReportKeepsTheHandlersWrite() throws Exception {

    final var aggregate = transactionTemplate.execute(status -> {
      final var scheduled = new Aggregate();
      scheduled.setContent("reported-and-kept");
      return processService.startWorkflow(scheduled);
    });
    assertNotNull(aggregate);

    // the counterpart: without a failure behind it the handler's write is the whole point
    // of letting a provider note down what it reported, and nothing of a transaction is
    // open here - VanillaBP opens one
    assertTrue(
        extension.runTheReportingHandler("test-module", PROCESS, aggregate.getId().toString()).isPresent(),
        "no handler of the extension serves that element");

    assertEquals(
        SampleWorkflowService.REPORTED_BY_THE_HANDLER,
        aggregates
            .findById(aggregate.getId())
            .orElseThrow()
            .getReported());

  }

}
