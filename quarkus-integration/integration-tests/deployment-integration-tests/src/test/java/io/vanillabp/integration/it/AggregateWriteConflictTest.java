package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.integration.adapter.migration.transaction.AggregateWrite;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.deployment.conflict.ConcurrentBranch;
import io.vanillabp.integration.test.deployment.conflict.ConflictAggregate;
import io.vanillabp.integration.test.deployment.conflict.ConflictAggregatePersistence;
import io.vanillabp.integration.test.deployment.conflict.ConflictProcessWiringSource;
import io.vanillabp.integration.test.deployment.conflict.ConflictWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Concurrent tokens on Quarkus, with a real JPA aggregate carrying <code>@Version</code>: while
 * the <code>&#64;WorkflowTask</code> handler runs, a second writer changes the same row
 * in a transaction of its own and commits - two branches of one workflow, made
 * sequential so nothing here depends on a race.
 * <p>
 * Under JTA the conflict never arrives as itself: Hibernate raises it while flushing at
 * the commit, Narayana reports a <code>RollbackException</code> and
 * <code>QuarkusTransaction</code> wraps that again. So this test proves the unwrapping
 * as much as the message.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AggregateWriteConflictTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "ConflictProcess";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("aggregate-conflict/application.yaml", "application.yaml")
          .addClass(ConflictAggregate.class)
          .addClass(ConflictAggregatePersistence.class)
          .addClass(ConcurrentBranch.class)
          .addClass(ConflictWorkflowService.class)
          .addClass(ConflictProcessWiringSource.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/ConflictProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  ConflictAggregatePersistence persistence;

  @Inject
  @Any
  Instance<List<AdapterDeploymentService<Object, Object>>> deploymentServices;

  @Test
  @DisplayName("A version conflict in VanillaBP's own commit is named and reaches the adapter")
  public void aVersionConflictIsReportedAndPropagated() {

    final var dummyAdapter = dummyAdapter();
    persistence.seed("6001");

    final var messages = new java.util.ArrayList<String>();
    final var thrown = assertThrows(
        RuntimeException.class,
        () -> loggedBy(
            messages,
            () -> dummyAdapter.invokeTask(MODULE, PROCESS, context("conflictingTask", "6001"))));

    // the exception the platform recognized: an optimistic locking failure somewhere
    // in the chain of causes, which is how JTA delivers it
    assertTrue(AggregateWrite.causedByOptimisticLocking(thrown), thrown.toString());
    assertEquals(1, messages.size(), messages.toString());
    final var message = messages.getFirst();
    assertTrue(message.contains(MODULE), message);
    assertTrue(message.contains(PROCESS), message);
    assertTrue(message.contains("6001"), message);
    assertTrue(message.contains("conflictingTask"), message);
    assertTrue(message.contains("does not retry"), message);

    // what the other branch wrote survived, what this run changed did not
    assertEquals("changed by the other branch", persistence.storedContent("6001"));

  }

  @Test
  @DisplayName("A task nobody writes against commits as usual and says nothing")
  public void anUndisturbedTaskStaysSilent() {

    final var dummyAdapter = dummyAdapter();
    persistence.seed("6002");

    final var messages = new java.util.ArrayList<String>();
    final var outcome = new WorkflowTaskOutcome[1];
    loggedBy(
        messages,
        () -> outcome[0] = dummyAdapter.invokeTask(MODULE, PROCESS, context("undisturbedTask", "6002")));

    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, outcome[0].kind());
    assertTrue(messages.isEmpty(), messages.toString());
    assertEquals("changed by the task", persistence.storedContent("6002"));

  }

  private DummyDeploymentService dummyAdapter() {

    return deploymentServices
        .stream()
        .filter(java.util.Objects::nonNull)
        .flatMap(List::stream)
        .filter(java.util.Objects::nonNull)
        .filter(DummyDeploymentService.class::isInstance)
        .map(DummyDeploymentService.class::cast)
        .filter(service -> "demo1".equals(service.getAdapterId()))
        .findFirst()
        .orElseThrow();

  }

  private TaskInvocationContext context(
      final String taskDefinition,
      final String aggregateId) {

    return new TaskInvocationContext() {

      @Override
      public String getTaskDefinition() {
        return taskDefinition;
      }

      @Override
      public String getWorkflowAggregateId() {
        return aggregateId;
      }

    };

  }

  private void loggedBy(
      final List<String> messages,
      final Runnable work) {

    final var handler = new java.util.logging.Handler() {

      @Override
      public void publish(
          final java.util.logging.LogRecord record) {
        if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
          final var parameters = record.getParameters();
          messages
              .add(
                  (parameters == null) || (parameters.length == 0)
                      ? record.getMessage()
                      : String.format(record.getMessage(), parameters));
        }
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }

    };
    final var logger = java.util.logging.Logger.getLogger(AggregateWrite.class.getName());
    logger.addHandler(handler);
    try {
      work.run();
    } finally {
      logger.removeHandler(handler);
    }

  }

}
