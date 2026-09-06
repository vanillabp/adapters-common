package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.apptx.AppTxAggregate;
import io.vanillabp.integration.test.apptx.AppTxDeliveryLog;
import io.vanillabp.integration.test.apptx.AppTxOutbox;
import io.vanillabp.integration.test.apptx.AppTxPersistence;
import io.vanillabp.integration.test.apptx.AppTxStored;
import io.vanillabp.integration.test.apptx.AppTxTransactionRunner;
import io.vanillabp.integration.test.apptx.AppTxTransactions;
import io.vanillabp.integration.test.apptx.AppTxWiringSource;
import io.vanillabp.integration.test.apptx.AppTxWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Acceptance test on Quarkus: an application bringing ALL THREE stores itself -
 * the workflow aggregate, the phase-two outbox and the log of processed task deliveries -
 * with neither a data source nor MongoDB anywhere.
 * <p>
 * Quarkus always has a JTA transaction available, so the interesting part is not that the
 * workflow runs but WHOSE transaction it runs in: the aggregates implement one interface, one
 * {@link AppTxTransactions} bean names that interface, and the counters of the application's
 * runner prove that VanillaBP opened, committed and rolled IT back - not the empty JTA
 * transaction which would have looked just as green.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ApplicationOwnedStoresTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("apptx/application.yaml", "application.yaml")
          .addClass(AppTxStored.class)
          .addClass(AppTxAggregate.class)
          .addClass(AppTxPersistence.class)
          .addClass(AppTxTransactionRunner.class)
          .addClass(AppTxTransactions.class)
          .addClass(AppTxOutbox.class)
          .addClass(AppTxDeliveryLog.class)
          .addClass(AppTxWorkflowService.class)
          .addClass(AppTxWiringSource.class)
          .addAsResource(new StringAsset("not parsed by the dummy adapter"), "processes/dummy/TestProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      // a remote BPMS, so a start needs the phase-two outbox of the application
      .overrideConfigKey("dummy-adapter.at-least-once-delivery", "true");

  @Inject
  AppTxWorkflowService workflowService;

  @Inject
  AppTxPersistence persistence;

  @Inject
  AppTxOutbox outbox;

  @Inject
  AppTxDeliveryLog deliveryLog;

  @Inject
  AppTxTransactionRunner runner;

  @Inject
  @Any
  Instance<List<AdapterDeploymentService<Object, Object>>> deploymentServices;

  @Inject
  @Any
  Instance<io.vanillabp.integration.spi.TransactionRunnerAware<?>> transactionRunnerAwares;

  @Inject
  @Any
  Instance<io.vanillabp.integration.spi.TransactionRunner> transactionRunners;

  @Inject
  @Any
  Instance<io.vanillabp.integration.spi.AggregatePersistenceAware<?>> aggregatePersistences;

  /** Unsatisfied here: this application has no MongoDB client extension. */
  @Inject
  Instance<io.vanillabp.integration.runtime.processservice.MongoDeploymentProbe> mongoDeploymentProbes;

  private DummyDeploymentService dummyAdapter() {

    return deploymentServices
        .stream()
        .filter(java.util.Objects::nonNull)
        .flatMap(List::stream)
        .filter(java.util.Objects::nonNull)
        .filter(DummyDeploymentService.class::isInstance)
        .map(DummyDeploymentService.class::cast)
        .findFirst()
        .orElseThrow();

  }

  private TaskInvocationContext context(
      final String taskDefinition,
      final String aggregateId,
      final String deliveryId) {

    return new TaskInvocationContext() {

      @Override
      public String getAdapterId() {
        return "test";
      }

      @Override
      public String getTaskDefinition() {
        return taskDefinition;
      }

      @Override
      public String getWorkflowAggregateId() {
        return aggregateId;
      }

      @Override
      public String getDeliveryId() {
        return deliveryId;
      }

    };

  }

  @Test
  @DisplayName("Three own stores, no data source, no MongoDB: the workflow runs in the application's transaction")
  public void threeOwnStoresRunInTheApplicationsTransaction() {

    persistence.clear();
    outbox.clear();
    deliveryLog.clear();
    runner.reset();

    // (a) the two-phase start, inside the unit of work of the application
    final var started = workflowService.startWorkflow("app-tx-1");
    assertNotNull(started);
    assertEquals(1, outbox.getScheduled().size(), "the application's outbox got no entry");
    // twice: the start itself, and phase two which the outbox dispatched after the commit -
    // that one asks for a transaction as well, and gets the application's
    assertEquals(2, runner.getOpened(), "VanillaBP did not use the application's transaction");
    assertEquals(2, runner.getCommitted());
    assertEquals(0, runner.getRolledBack());
    assertEquals("started", persistence.stored("app-tx-1").getStatus());

    // (b) a task delivery: loaded, handled and saved inside the application's transaction
    runner.reset();
    final var outcome = dummyAdapter()
        .invokeTask("test-module", "TestProcess", context("processTask", "app-tx-1", "job-1"));
    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, outcome.kind());
    assertEquals(1, runner.getOpened(), "the task did not run in the application's transaction");
    assertEquals(1, runner.getCommitted());
    assertEquals("processed", persistence.stored("app-tx-1").getStatus());
    assertEquals(1, persistence.stored("app-tx-1").getInvocations());
    assertEquals(1, deliveryLog.size(), "the delivery was not recorded in the application's log");

    // (c) the repeated delivery is answered from the log without running the handler
    runner.reset();
    dummyAdapter().invokeTask("test-module", "TestProcess", context("processTask", "app-tx-1", "job-1"));
    assertEquals(
        1,
        persistence.stored("app-tx-1").getInvocations(),
        "the repeated delivery ran the handler again");

    // (d) a failing handler rolls the application's transaction back
    runner.reset();
    assertThrows(
        RuntimeException.class,
        () -> dummyAdapter()
            .invokeTask("test-module", "TestProcess", context("failingTask", "app-tx-1", "job-2")));
    assertEquals(1, runner.getRolledBack(), "the application's transaction was not rolled back");
    assertEquals("processed", persistence.stored("app-tx-1").getStatus());

  }

  @Test
  @DisplayName("The startup line names the classes of the application, not the container's proxies")
  public void theStartupLineNamesTheApplicationsClasses() {

    // the resolver of the platform, wired with the very beans of this application: what it
    // says about them is what the INFO line carries
    final var resolver = new io.vanillabp.integration.runtime.processservice.QuarkusTransactionRunnerResolver(
        transactionRunnerAwares, transactionRunners, aggregatePersistences, mongoDeploymentProbes, runner);

    final var aware = resolver.describeResolutionFor(AppTxAggregate.class);
    assertEquals(
        "the TransactionRunnerAware bean '%s' of the application".formatted(AppTxTransactions.class.getName()),
        aware,
        aware);

    // an aggregate no aware bean covers falls to the plain runner bean - named the same way
    final var plainBean = resolver.describeResolutionFor(Object.class);
    assertEquals(
        "the TransactionRunner bean '%s' of the application".formatted(AppTxTransactionRunner.class.getName()),
        plainBean,
        plainBean);

  }

  @Test
  @DisplayName("Starting a workflow outside the application's unit of work is refused")
  public void startWithoutTheApplicationsTransactionIsRefused() {

    persistence.clear();
    outbox.clear();

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> workflowService.startWorkflowWithoutTransaction("app-tx-2"));

    assertTrue(failure.getMessage().contains("No transaction is active"), failure.getMessage());
    assertNull(persistence.stored("app-tx-2"));
    assertTrue(outbox.getScheduled().isEmpty());

  }

}
