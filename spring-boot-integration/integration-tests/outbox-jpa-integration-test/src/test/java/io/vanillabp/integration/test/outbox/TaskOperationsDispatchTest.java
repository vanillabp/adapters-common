package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.process.TaskNotFoundException;

/**
 * The asynchronous-task operations {@code completeTask}/{@code cancelTask} through
 * the phase-two outbox, using the dummy adapter forced to require a
 * two-phase commit:
 * <ul>
 * <li>the adapter is elected by probing ({@code awarenessOfTask}) and phase two is
 * dispatched AFTER the commit (task ID and BPMN error code travel in the outbox
 * entry's args);</li>
 * <li>a rollback leaves no entry - phase two never runs;</li>
 * <li>an unknown task raises the guiding {@link TaskNotFoundException};</li>
 * <li>a task reported COMPLETED is an idempotent no-op (no outbox entry).</li>
 * </ul>
 */
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class TaskOperationsDispatchTest {

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private RecordingPhaseTwoListener listener;

  @Autowired
  private SteerableTaskAwarenessSource awareness;

  @Autowired
  private AggregateRepository repository;

  @Autowired
  private DataSource dataSource;

  /**
   * What gruelbox still owes for one aggregate. Its table names no aggregate of its own, but
   * the idempotency key of a workflow start ends with the aggregate id, which is enough to
   * tell this test's entries from those the classes before it left in the database they all
   * share. A BLOCKED entry is left out for the same reason: it waits for a person, so a test
   * which waited for it would wait for ever.
   */
  private static final String COUNT_ENTRIES_NOT_DISPATCHED = "SELECT COUNT(*) FROM TXNO_OUTBOX "
      + "WHERE processed = false AND blocked = false AND uniqueRequestId LIKE ?";

  @BeforeEach
  public void reset() {

    listener.reset();
    awareness.answerWith(WorkflowAwareness.UNKNOWN_TO_BPMS);
    // the awareness source is a bean of the cached context this module's test classes
    // share, so start from "the workflow is visible" instead of whatever the class
    // running before left behind
    awareness.alwaysVisible();

  }

  @AfterEach
  public void forgetWhatThisTestSteered() {

    // a window one test opened is closed by that test rather than by the next one: a test
    // which fails in between leaves it behind, and every workflow probe of whoever comes
    // next then reports "not visible yet" for nothing
    awareness.alwaysVisible();

  }

  /**
   * A workflow whose start is THROUGH: the outbox entry of its phase two is dispatched and
   * gruelbox marked it processed.
   * <p>
   * Waiting for the listener instead would answer a different question. The listener runs
   * INSIDE the dispatch, one write before the store marks the entry, so it says that the
   * adapter was called and not that the entry is finished. Everything the tests below do
   * next - steering what the adapter answers, planning a second operation on the same
   * workflow - reads as if the start were over, and only the store knows whether it is.
   *
   * @param content What the aggregate carries, one value per test
   * @return The attached aggregate
   */
  private Aggregate startedAggregate(
      final String content) throws Exception {

    final var attached = transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent(content);
      return processService.startWorkflow(aggregate);
    });
    assertNotNull(attached);
    awaitNothingLeftUndone(attached);
    return attached;

  }

  /**
   * Waits until the store has dispatched everything it owes for this aggregate.
   *
   * @param aggregate The aggregate whose entries have to be through
   */
  private void awaitNothingLeftUndone(
      final Aggregate aggregate) throws Exception {

    final var deadline = System.currentTimeMillis() + 30000;
    while (countEntriesNotDispatched(aggregate) > 0) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "an entry of aggregate '%s' was never dispatched".formatted(aggregate.getId()));
      Thread.sleep(50);
    }

  }

  private long countEntriesNotDispatched(
      final Aggregate aggregate) throws Exception {

    try (var connection = dataSource.getConnection(); var statement = connection
        .prepareStatement(COUNT_ENTRIES_NOT_DISPATCHED)) {
      statement.setString(1, "%|"
          + aggregate.getId());
      try (var resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1);
      }
    }

  }

  @Test
  @DisplayName("completeTask elects the adapter by probing and dispatches phase two after the commit")
  public void completeTaskDispatchesPhaseTwoAfterCommit() throws Exception {

    final var aggregate = startedAggregate("complete-task");
    awareness.answerWith(WorkflowAwareness.ACTIVE);

    transactionTemplate.executeWithoutResult(status -> processService
        .completeTask(aggregate, "task-77"));

    final var completions = listener.awaitCompletedTasks(1, 10000);
    assertEquals(
        aggregate.getId()
            + ":task-77",
        completions.getFirst());

  }

  @Test
  @DisplayName("cancelTask carries the BPMN error code through the outbox entry's args")
  public void cancelTaskDispatchesWithErrorCode() throws Exception {

    final var aggregate = startedAggregate("cancel-task");
    awareness.answerWith(WorkflowAwareness.ACTIVE);

    transactionTemplate.executeWithoutResult(status -> processService
        .cancelTask(aggregate, "task-78", "PAYMENT_FAILED"));

    final var cancellations = listener.awaitCanceledTasks(1, 10000);
    assertEquals(
        aggregate.getId()
            + ":task-78:PAYMENT_FAILED",
        cancellations.getFirst());

  }

  @Test
  @DisplayName("completeUserTask and cancelUserTask dispatch phase two after the commit")
  public void userTaskOperationsDispatchAfterCommit() throws Exception {

    final var aggregate = startedAggregate("user-task-ops");
    awareness.answerWith(WorkflowAwareness.ACTIVE);

    transactionTemplate.executeWithoutResult(status -> {
      processService.completeUserTask(aggregate, "utask-1");
      processService.cancelUserTask(aggregate, "utask-2", "APPROVAL_WITHDRAWN");
    });

    final var deadline = System.currentTimeMillis() + 10000;
    while (listener.getCompletedUserTasks().isEmpty() || listener.getCanceledUserTasks().isEmpty()) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "user-task operations were not dispatched in time");
      Thread.sleep(50);
    }
    assertEquals(
        aggregate.getId()
            + ":utask-1",
        listener.getCompletedUserTasks().getFirst());
    assertEquals(
        aggregate.getId()
            + ":utask-2:APPROVAL_WITHDRAWN",
        listener.getCanceledUserTasks().getFirst());

  }

  @Test
  @DisplayName("An unknown user task raises the guiding TaskNotFoundException")
  public void unknownUserTaskRaisesGuidingException() throws Exception {

    final var aggregate = startedAggregate("unknown-user-task");

    final var exception = assertThrowsExactly(
        TaskNotFoundException.class,
        () -> transactionTemplate.executeWithoutResult(status -> processService
            .completeUserTask(aggregate, "utask-unknown")));
    assertTrue(exception.getMessage().contains("utask-unknown"));

  }

  @Test
  @DisplayName("correlateMessage dispatches phase two after the commit (with and without correlation id)")
  public void correlateMessageDispatchesAfterCommit() throws Exception {

    final var aggregate = startedAggregate("correlate");
    awareness.answerWith(WorkflowAwareness.ACTIVE);

    transactionTemplate.executeWithoutResult(status -> {
      processService.correlateMessage(aggregate, "PaymentReceived");
      processService.correlateMessage(aggregate, "ItemShipped", "item-4711");
    });

    final var deadline = System.currentTimeMillis() + 10000;
    while (listener.getCorrelatedMessages().size() < 2) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "correlations were not dispatched in time: "
              + listener.getCorrelatedMessages());
      Thread.sleep(50);
    }
    assertTrue(listener
        .getCorrelatedMessages()
        .contains(aggregate.getId()
            + ":PaymentReceived:null"));
    assertTrue(listener
        .getCorrelatedMessages()
        .contains(aggregate.getId()
            + ":ItemShipped:item-4711"));

  }

  @Test
  @DisplayName("The same message WITHOUT a correlation id may be scheduled twice; WITH one it deduplicates")
  public void correlationIdempotency() throws Exception {

    final var aggregate = startedAggregate("correlate-dedup");
    awareness.answerWith(WorkflowAwareness.ACTIVE);

    transactionTemplate.executeWithoutResult(status -> {
      // no correlation id: NO idempotency key - both dispatch
      processService.correlateMessage(aggregate, "Ping");
      processService.correlateMessage(aggregate, "Ping");
      // with correlation id: second schedule is a no-op
      processService.correlateMessage(aggregate, "Pong", "p-1");
      processService.correlateMessage(aggregate, "Pong", "p-1");
    });

    final var deadline = System.currentTimeMillis() + 10000;
    while (listener.getCorrelatedMessages().size() < 3) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "correlations were not dispatched in time: "
              + listener.getCorrelatedMessages());
      Thread.sleep(50);
    }
    Thread.sleep(1500);
    final var pings = listener
        .getCorrelatedMessages()
        .stream()
        .filter(entry -> entry.contains(":Ping:"))
        .count();
    final var pongs = listener
        .getCorrelatedMessages()
        .stream()
        .filter(entry -> entry.contains(":Pong:"))
        .count();
    assertEquals(2, pings, "without a correlation id both correlations dispatch");
    assertEquals(1, pongs, "with a correlation id the duplicate schedule is a no-op");

  }

  @Test
  @DisplayName("Correlating with a workflow nobody started raises the guiding WorkflowNotFoundException")
  public void unknownWorkflowRaisesGuidingException() {

    // an aggregate which never went through startWorkflow: nothing ever recorded an
    // adapter for it, so "unknown" is the final answer rather than a read model
    // which is a moment behind
    final var aggregate = transactionTemplate.execute(status -> {
      final var created = new Aggregate();
      created.setContent("correlate-unknown");
      return repository.save(created);
    });
    assertNotNull(aggregate);
    // awareness stays UNKNOWN_TO_BPMS

    final var exception = assertThrowsExactly(
        io.vanillabp.spi.process.WorkflowNotFoundException.class,
        () -> transactionTemplate.executeWithoutResult(status -> processService
            .correlateMessage(aggregate, "PaymentReceived")));
    assertTrue(exception.getMessage().contains("PaymentReceived"));
    assertTrue(
        exception.getMessage().contains("startWorkflowByMessage"),
        "expected the start-by-message hint but got: "
            + exception.getMessage());

  }

  @Test
  @DisplayName("A workflow this node started is planned rather than refused while the BPMS catches up")
  public void aWorkflowStartedHereIsPlannedWhileTheBpmsCatchesUp() throws Exception {

    final var aggregate = startedAggregate("correlate-not-visible-yet");
    // the start recorded which adapter holds the workflow, and that adapter does not
    // report it yet - the everyday state of an exporter-fed read model
    awareness.answerWith(WorkflowAwareness.ACTIVE);
    awareness.becomeVisibleAfter(2, java.time.Duration.ofSeconds(5));

    transactionTemplate.executeWithoutResult(status -> processService
        .correlateMessage(aggregate, "PaymentReceived"));

    final var deadline = System.currentTimeMillis() + 10000;
    while (listener.getCorrelatedMessages().isEmpty()) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "the correlation was not dispatched in time");
      Thread.sleep(50);
    }
    assertTrue(listener
        .getCorrelatedMessages()
        .contains(aggregate.getId()
            + ":PaymentReceived:null"));
  }

  @Test
  @DisplayName("A COMPLETED workflow makes correlateMessage a warned no-op")
  public void completedWorkflowCorrelationIsNoOp() throws Exception {

    final var aggregate = startedAggregate("correlate-completed");
    awareness.answerWith(WorkflowAwareness.COMPLETED);

    transactionTemplate.executeWithoutResult(status -> processService
        .correlateMessage(aggregate, "PaymentReceived"));

    Thread.sleep(1500);
    assertTrue(listener.getCorrelatedMessages().isEmpty());

  }

  @Test
  @DisplayName("startWorkflowByMessage uses start semantics: first adapter, persisted adapter id")
  public void startWorkflowByMessageDispatchesAfterCommit() throws Exception {

    final var attached = transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent("start-by-message");
      return processService.startWorkflowByMessage(aggregate, "OrderPlaced");
    });
    assertNotNull(attached);

    final var deadline = System.currentTimeMillis() + 10000;
    while (listener.getStartedByMessage().isEmpty()) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "start-by-message was not dispatched in time");
      Thread.sleep(50);
    }
    assertEquals(
        attached.getId()
            + ":OrderPlaced",
        listener.getStartedByMessage().getFirst());

  }

  @Test
  @DisplayName("A rollback leaves no outbox entry - phase two of the completion never runs")
  public void rollbackLeavesNoCompletion() throws Exception {

    final var aggregate = startedAggregate("rollback-complete");
    awareness.answerWith(WorkflowAwareness.ACTIVE);

    final var exception = assertThrowsExactly(
        RuntimeException.class,
        () -> transactionTemplate.executeWithoutResult(status -> {
          processService.completeTask(aggregate, "task-79");
          throw new RuntimeException("test rollback");
        }));
    assertEquals("test rollback", exception.getMessage());

    Thread.sleep(1500);
    assertTrue(listener.getCompletedTasks().isEmpty(), "phase two must never run after a rollback");

  }

  @Test
  @DisplayName("No adapter knows the task - the guiding TaskNotFoundException is raised")
  public void unknownTaskRaisesGuidingException() throws Exception {

    final var aggregate = startedAggregate("unknown-task");
    // awareness stays UNKNOWN_TO_BPMS

    final var exception = assertThrowsExactly(
        TaskNotFoundException.class,
        () -> transactionTemplate.executeWithoutResult(status -> processService
            .completeTask(aggregate, "task-unknown")));

    assertTrue(exception.getMessage().contains("task-unknown"));
    assertTrue(
        exception.getMessage().contains("prioritized"),
        "expected the probed adapters to be mentioned but got: "
            + exception.getMessage());

  }

  @Test
  @DisplayName("A task reported COMPLETED is an idempotent no-op - no phase two is scheduled")
  public void completedTaskIsIdempotentNoOp() throws Exception {

    final var aggregate = startedAggregate("already-completed");
    awareness.answerWith(WorkflowAwareness.COMPLETED);

    final var result = transactionTemplate.execute(status -> processService
        .completeTask(aggregate, "task-80"));

    assertNotNull(result);
    Thread.sleep(1500);
    assertTrue(listener.getCompletedTasks().isEmpty(), "an already-completed task must not dispatch");

  }

}
