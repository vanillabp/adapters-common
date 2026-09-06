package io.vanillabp.integration.test.outbox.conflict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;

import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.integration.adapter.migration.transaction.AggregateWrite;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.outbox.TestApplication;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Concurrent tokens on Spring Boot, with a real JPA aggregate carrying <code>@Version</code>:
 * while the <code>@WorkflowTask</code> handler runs, a second writer changes the same
 * row in a transaction of its own and commits - the shape of two branches of one
 * workflow, made sequential so nothing here depends on a race.
 * <p>
 * VanillaBP's commit then fails: the platform recognizes the conflict, one guiding
 * message names it, and the exception reaches the adapter unchanged, which is what
 * lets the BPMS apply its retry semantics.
 */
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class AggregateWriteConflictTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "ConflictProcess";

  @Autowired
  private ConflictAggregateRepository repository;

  @Autowired
  private DummyDeploymentService dummyAdapter;

  @Test
  @DisplayName("A version conflict in VanillaBP's own commit is named and reaches the adapter")
  public void aVersionConflictIsReportedAndPropagated() {

    final var aggregate = new ConflictAggregate();
    aggregate.setContent("new");
    final var aggregateId = repository
        .save(aggregate)
        .getId();

    final var messages = new java.util.ArrayList<String>();
    final var thrown = assertThrows(
        OptimisticLockingFailureException.class,
        () -> loggedBy(
            messages,
            () -> dummyAdapter
                .invokeTask(MODULE, PROCESS, context("conflictingTask", aggregateId))));

    assertEquals(1, messages.size(), messages.toString());
    final var message = messages.getFirst();
    assertTrue(message.contains(MODULE), message);
    assertTrue(message.contains(PROCESS), message);
    assertTrue(message.contains(aggregateId.toString()), message);
    assertTrue(message.contains("conflictingTask"), message);
    assertTrue(message.contains("does not retry"), message);
    // not wrapped: the adapter needs the exception its BPMS' retry semantics know
    assertTrue(
        thrown.getMessage().contains(ConflictAggregate.class.getName()),
        thrown.getMessage());

    // what the other branch wrote survived, what this run changed did not
    assertEquals(
        "changed by the other branch",
        repository
            .findById(aggregateId)
            .orElseThrow()
            .getContent());

  }

  @Test
  @DisplayName("A task nobody writes against commits as usual and says nothing")
  public void anUndisturbedTaskStaysSilent() {

    final var aggregate = new ConflictAggregate();
    aggregate.setContent("new");
    final var aggregateId = repository
        .save(aggregate)
        .getId();

    final var messages = new java.util.ArrayList<String>();
    final var outcome = new WorkflowTaskOutcome[1];
    loggedBy(
        messages,
        () -> outcome[0] = dummyAdapter
            .invokeTask(MODULE, PROCESS, context("undisturbedTask", aggregateId)));

    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, outcome[0].kind());
    assertTrue(messages.isEmpty(), messages.toString());
    assertEquals(
        "changed by the task",
        repository
            .findById(aggregateId)
            .orElseThrow()
            .getContent());

  }

  private TaskInvocationContext context(
      final String taskDefinition,
      final Long aggregateId) {

    return new TaskInvocationContext() {

      @Override
      public String getTaskDefinition() {
        return taskDefinition;
      }

      @Override
      public String getWorkflowAggregateId() {
        return aggregateId.toString();
      }

    };

  }

  private void loggedBy(
      final List<String> messages,
      final Runnable work) {

    final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logWatcher.start();
    final var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AggregateWrite.class);
    logger.addAppender(logWatcher);
    try {
      work.run();
    } finally {
      logger.detachAndStopAllAppenders();
      logWatcher.list
          .stream()
          .filter(event -> event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN))
          .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
          .forEach(messages::add);
    }

  }

}
