package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.deployment.NestedTransactionalBean;
import io.vanillabp.integration.test.deployment.TransactionAggregate;
import io.vanillabp.integration.test.deployment.TransactionAggregatePersistence;
import io.vanillabp.integration.test.deployment.TransactionProcessWiringSource;
import io.vanillabp.integration.test.deployment.TransactionWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Acceptance test of the transaction-contract safeguards on Quarkus: a
 * transaction annotation of the application in the call chain of a
 * <code>&#64;WorkflowTask</code> handler marks VanillaBP's JTA transaction
 * rollback-only, after which neither the aggregate changes nor the state of the BPMS can
 * be committed. Narayana refuses the commit of a transaction in state
 * {@code STATUS_MARKED_ROLLBACK}, so without the check the developer would be left with
 * Arjuna's wording one layer away from the cause.
 * <p>
 * The startup check covering an annotation ON the handler is asserted by the deployment
 * module's {@code TransactionAnnotationStartupTest}; the annotation matrix is covered by
 * the core's unit tests.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ApplicationTransactionTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TransactionProcess";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("transaction/application.yaml", "application.yaml")
          .addClass(TransactionAggregate.class)
          .addClass(TransactionAggregatePersistence.class)
          .addClass(NestedTransactionalBean.class)
          .addClass(TransactionWorkflowService.class)
          .addClass(TransactionProcessWiringSource.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/TransactionProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  TransactionAggregatePersistence persistence;

  @Inject
  @Any
  Instance<List<AdapterDeploymentService<Object, Object>>> deploymentServices;

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
      final String aggregateId,
      final boolean runInCurrentTransaction) {

    return new TaskInvocationContext() {

      @Override
      public String getTaskDefinition() {
        return taskDefinition;
      }

      @Override
      public String getWorkflowAggregateId() {
        return aggregateId;
      }

      @Override
      public boolean runInCurrentTransaction() {
        return runInCurrentTransaction;
      }

    };

  }

  private void assertGuidingMessage(
      final IllegalStateException failure,
      final String taskDefinition) {

    assertTrue(failure.getMessage().contains("marked rollback-only"), failure.getMessage());
    assertTrue(failure.getMessage().contains(taskDefinition), failure.getMessage());
    assertTrue(failure.getMessage().contains(PROCESS), failure.getMessage());
    assertTrue(failure.getMessage().contains(MODULE), failure.getMessage());
    // the rollback rule as it is written on THIS platform: Quarkus honors the JTA
    // annotation, and Spring's only with the extension quarkus-spring-tx, which this
    // application does not have - so its attribute must not be offered here
    assertTrue(failure.getMessage().contains("dontRollbackOn = TaskException.class"), failure.getMessage());
    assertFalse(failure.getMessage().contains("noRollbackFor"), failure.getMessage());

  }

  @Test
  @DisplayName("A transaction annotation in the call chain fails the task instead of losing the changes")
  public void rollbackOnlyTransactionsAreReported() {

    final var dummyAdapter = dummyAdapter();

    // (a) the shape of a remote BPMS: VanillaBP opens its own transaction, the
    // nested bean joins it and takes it down with the TaskException
    persistence.seed("5001");
    final var ownTransaction = assertThrows(
        IllegalStateException.class,
        () -> dummyAdapter.invokeTask(MODULE, PROCESS, context("nestedTaskException", "5001", false)));
    assertGuidingMessage(ownTransaction, "nestedTaskException");

    // (b) the shape of an embedded engine: VanillaBP participates in the caller's
    // transaction
    persistence.seed("5002");
    final var joinedTransaction = assertThrows(
        IllegalStateException.class,
        () -> QuarkusTransaction
            .requiringNew()
            .call(() -> dummyAdapter.invokeTask(MODULE, PROCESS, context("nestedTaskException", "5002", true))));
    assertGuidingMessage(joinedTransaction, "nestedTaskException");

    // (c) no TaskException involved at all: the handler swallowed the exception of
    // the nested call and returned normally, which would report the task as
    // completed while nothing can be persisted
    persistence.seed("5003");
    final var swallowed = assertThrows(
        IllegalStateException.class,
        () -> dummyAdapter.invokeTask(MODULE, PROCESS, context("swallowedNestedFailure", "5003", false)));
    assertGuidingMessage(swallowed, "swallowedNestedFailure");

    // (d) the annotation a version-1 application carries: accepted by the startup
    // check (this application booted with it) and working at runtime
    persistence.seed("5004");
    final var accepted = dummyAdapter.invokeTask(MODULE, PROCESS, context("acceptedAnnotation", "5004", false));
    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, accepted.kind());
    assertEquals("accepted", persistence.stored("5004").getStatus());

  }

}
