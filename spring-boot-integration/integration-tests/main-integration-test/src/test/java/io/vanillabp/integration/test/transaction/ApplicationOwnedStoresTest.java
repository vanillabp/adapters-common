package io.vanillabp.integration.test.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.spi.TransactionRunnerAware;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.deployment.DeploymentTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.spi.process.ProcessService;

/**
 * Acceptance test on Spring Boot: an application which brings ALL THREE stores
 * itself - the workflow aggregate, the phase-two outbox and the log of processed task
 * deliveries - with no data source and no MongoDB anywhere, and therefore no
 * {@link PlatformTransactionManager} at all.
 * <p>
 * Such an application used to boot and then refuse every workflow: a start
 * failed with "No transaction is active!" and every task delivery with "No (unique)
 * PlatformTransactionManager is available", a message which recommended adding a relational
 * database. Now the application contributes its unit of work through a
 * {@link TransactionRunnerAware} bean naming the INTERFACE its aggregates implement, and the
 * counters of that runner are the assertion: VanillaBP opens it, commits it and rolls it
 * back around everything it does.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ApplicationOwnedStoresTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "AppTxProcess";

  private static final String ADAPTER = "test";

  /**
   * The application's unit of work: it counts what VanillaBP does with it and runs
   * after-commit work, which is what an outbox needs to dispatch phase two.
   */
  static class CountingTransactionRunner implements TransactionRunner {

    final AtomicInteger opened = new AtomicInteger();

    final AtomicInteger committed = new AtomicInteger();

    final AtomicInteger rolledBack = new AtomicInteger();

    private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);

    private final ThreadLocal<List<Runnable>> afterCommit = ThreadLocal.withInitial(ArrayList::new);

    void reset() {

      opened.set(0);
      committed.set(0);
      rolledBack.set(0);

    }

    void afterCommit(
        final Runnable work) {

      afterCommit.get().add(work);

    }

    @Override
    public <T> T requireNew(
        final Supplier<T> work) {

      opened.incrementAndGet();
      depth.set(depth.get() + 1);
      try {
        final var result = work.get();
        depth.set(depth.get() - 1);
        committed.incrementAndGet();
        if (depth.get() == 0) {
          final var pending = List.copyOf(afterCommit.get());
          afterCommit.get().clear();
          pending.forEach(Runnable::run);
        }
        return result;
      } catch (final RuntimeException failure) {
        depth.set(depth.get() - 1);
        rolledBack.incrementAndGet();
        if (depth.get() == 0) {
          afterCommit.get().clear();
        }
        throw failure;
      }

    }

    @Override
    public <T> T inCurrent(
        final Supplier<T> work) {

      if (depth.get() == 0) {
        throw new IllegalStateException("no unit of work of the application is open");
      }
      return work.get();

    }

    @Override
    public <T> T requireTransaction(
        final Supplier<T> work) {

      return depth.get() > 0
          ? work.get()
          : requireNew(work);

    }

    @Override
    public boolean isTransactionActive() {

      return depth.get() > 0;

    }

    @Override
    public boolean isRollbackOnly() {

      return false;

    }

  }

  @Configuration
  static class ApplicationOwnedStoresConfiguration {

    static final Map<String, AppTxAggregate> AGGREGATES = new ConcurrentHashMap<>();

    static final List<PhaseTwoCall> SCHEDULED = new ArrayList<>();

    static final Map<String, TaskDelivery> DELIVERIES = new ConcurrentHashMap<>();

    static final CountingTransactionRunner RUNNER = new CountingTransactionRunner();

    static void reset() {

      AGGREGATES.clear();
      SCHEDULED.clear();
      DELIVERIES.clear();
      RUNNER.reset();
      FALLBACK_RUNNER.reset();
      PHASE_TWO.clear();

    }

    static final CountingTransactionRunner FALLBACK_RUNNER = new CountingTransactionRunner();

    static final List<String> PHASE_TWO = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Records what the dummy adapter did in phase two - the proof that the outbox of the
     * application dispatched after ITS commit.
     */
    @Bean
    io.vanillabp.bpmsdouble.DummyPhaseTwoListener appTxPhaseTwoRecorder() {

      return aggregateId -> PHASE_TWO.add(String.valueOf(aggregateId));

    }

    /**
     * The runner of the application for every aggregate no aware bean covers - the second
     * of the four resolution steps. The test classpath brings the aggregates of other
     * tests along, and this is what a real application would do for all of them.
     */
    @Bean
    TransactionRunner appTxFallbackTransactions() {

      return FALLBACK_RUNNER;

    }

    /**
     * One aware bean for every aggregate of the workflow module, attributed through the
     * interface they share.
     */
    @Bean
    TransactionRunnerAware<AppTxStored> appTxTransactions() {

      return new TransactionRunnerAware<>() {

        @Override
        public Class<AppTxStored> getAggregateClass() {
          return AppTxStored.class;
        }

        @Override
        public TransactionRunner getTransactionRunner() {
          return RUNNER;
        }

      };

    }

    @Bean
    AggregatePersistenceAware<AppTxAggregate> appTxPersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<AppTxAggregate> getAggregateClass() {
          return AppTxAggregate.class;
        }

        @Override
        public AppTxAggregate save(
            final AppTxAggregate aggregate) {
          if (aggregate.getId() == null) {
            aggregate.setId(UUID.randomUUID().toString());
          }
          AGGREGATES.put(aggregate.getId(), copyOf(aggregate));
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final AppTxAggregate aggregate) {
          return aggregate.getId();
        }

        @Override
        public Class<?> getAggregateIdType() {
          return String.class;
        }

        @Override
        public AppTxAggregate loadById(
            final Object aggregateId) {
          final var stored = AGGREGATES.get(aggregateId);
          return stored != null
              ? copyOf(stored)
              : null;
        }

      };

    }

    /**
     * The application's outbox: the entry becomes visible with the commit of the
     * application's unit of work, and the dispatch happens right after it - the contract
     * of {@link PhaseTwoOutbox} without a database.
     */
    @Bean
    PhaseTwoOutbox appTxOutbox(
        final PhaseTwoRouter router) {

      return call -> {
        // deliberately simpler than a real store: nothing here is ever marked done, so
        // a key deduplicates for as long as this context lives, while a real store
        // deduplicates the entries still waiting for their dispatch
        final var idempotencyKey = call
            .idempotencyKey()
            .orElse(null);
        synchronized (SCHEDULED) {
          if ((idempotencyKey != null) && SCHEDULED
              .stream()
              .anyMatch(scheduled -> idempotencyKey
                  .equals(
                      scheduled
                          .idempotencyKey()
                          .orElse(null)))) {
            return false;
          }
          SCHEDULED.add(call);
        }
        RUNNER.afterCommit(() -> router.dispatch(call));
        return true;
      };

    }

    @Bean
    TaskDeliveryLog appTxDeliveryLog() {

      return new TaskDeliveryLog() {

        @Override
        public Optional<TaskDelivery> recordedDelivery(
            final String deliveryKey) {
          return Optional.ofNullable(DELIVERIES.get(deliveryKey));
        }

        @Override
        public boolean record(
            final TaskDelivery delivery) {
          return DELIVERIES.putIfAbsent(delivery.deliveryKey(), delivery) == null;
        }

      };

    }

    /**
     * Stands in for the BPMN model of 'AppTxProcess'.
     */
    @Bean
    DummyTaskWiringSource appTxTaskWiringSource() {

      return (
          adapterId,
          workflowModuleId,
          bpmnProcessId) -> PROCESS.equals(bpmnProcessId)
              ? List.of(
                  new BpmnTaskSpec("Activity_Handle", "handleTask"),
                  new BpmnTaskSpec("Activity_Fail", "failingTask"))
              : List.of();

    }

    private static AppTxAggregate copyOf(
        final AppTxAggregate aggregate) {

      final var copy = new AppTxAggregate();
      copy.setId(aggregate.getId());
      copy.setStatus(aggregate.getStatus());
      copy.setInvocations(aggregate.getInvocations());
      return copy;

    }

  }

  private static final String APPLICATION_YAML = """
      dummy-adapter:
        at-least-once-delivery: true
      vanillabp:
        adapters:
          test:
            type: dummy
            test: 1
        workflow-modules:
          test-module:
            adapters:
              test:
                resources-location: classpath*:test-module/processes/apptransaction
      """;

  private ConfigurableApplicationContext runTestApplication(
      final SpringBootTestApplication testApp) {

    return testApp
        .applicationBuilder(
            DummyAdapterConfiguration.class,
            DummyAdapterProcessServiceConfiguration.class,
            WorkflowModuleAutoConfiguration.class,
            SpringBootMigrationAdapterAutoConfiguration.class,
            TestPersistenceConfiguration.class,
            AppTxWorkflowService.class,
            WorkflowModuleConfiguration.class,
            ApplicationOwnedStoresConfiguration.class,
            DeploymentTest.TestConfig.class)
        .run();

  }

  private SpringBootTestApplication buildTestApp() throws IOException {

    return SpringBootTestApplication
        .builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", APPLICATION_YAML)
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build();

  }

  @SuppressWarnings("unchecked")
  private ProcessService<AppTxAggregate> processService(
      final ConfigurableApplicationContext context) {

    return context
        .getBean(
            "VanillaBP_ProcessService_"
                + AppTxAggregate.class.getName(),
            ProcessService.class);

  }

  private DummyDeploymentService dummyAdapter(
      final ConfigurableApplicationContext context) {

    return context.getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);

  }

  private TaskInvocationContext delivery(
      final String taskDefinition,
      final String aggregateId,
      final String deliveryId) {

    return new TaskInvocationContext() {

      @Override
      public String getAdapterId() {
        return ADAPTER;
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
  @DisplayName("The application boots and works with three own stores and no transaction manager")
  public void threeOwnStoresAndNoTransactionManager() throws IOException {

    ApplicationOwnedStoresConfiguration.reset();

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      // no relational database, no MongoDB, hence nothing Spring would manage
      assertEquals(
          0,
          context.getBeanNamesForType(PlatformTransactionManager.class).length,
          "this test is pointless with a transaction manager around");

      final var runner = ApplicationOwnedStoresConfiguration.RUNNER;

      // (a) a two-phase workflow start inside the application's own unit of work
      final var started = runner.requireNew(() -> {
        final var aggregate = new AppTxAggregate();
        aggregate.setStatus("new");
        return processService(context).startWorkflow(aggregate);
      });
      assertNotNull(started.getId());
      assertEquals(1, ApplicationOwnedStoresConfiguration.SCHEDULED.size(), "no outbox entry was scheduled");
      assertEquals(
          List.of(started.getId()),
          ApplicationOwnedStoresConfiguration.PHASE_TWO,
          "phase two was not dispatched after the commit of the application's transaction");
      // twice: the start itself, and phase two - which asks for a transaction as well and
      // gets the application's
      assertEquals(2, runner.opened.get());
      assertEquals(2, runner.committed.get());
      assertEquals(0, runner.rolledBack.get());
      // the aware bean naming the interface wins over the application-wide runner bean
      assertEquals(
          0,
          ApplicationOwnedStoresConfiguration.FALLBACK_RUNNER.opened.get(),
          "the fallback runner was used although an aware bean covers this aggregate");

      // (b) a task delivery: the handler runs in a transaction of the application, the
      // aggregate is saved and the delivery is recorded - all through its own stores
      runner.reset();
      final var outcome = dummyAdapter(context)
          .invokeTask(MODULE, PROCESS, delivery("handleTask", started.getId(), "job-1"));
      assertNotNull(outcome);
      assertEquals(1, runner.opened.get(), "VanillaBP did not use the application's transaction");
      assertEquals(1, runner.committed.get());
      assertEquals(
          1,
          ApplicationOwnedStoresConfiguration.AGGREGATES.get(started.getId()).getInvocations());
      assertEquals(1, ApplicationOwnedStoresConfiguration.DELIVERIES.size());

      // (c) the repeated delivery of the same task does not run the handler again
      runner.reset();
      dummyAdapter(context).invokeTask(MODULE, PROCESS, delivery("handleTask", started.getId(), "job-1"));
      assertEquals(
          1,
          ApplicationOwnedStoresConfiguration.AGGREGATES.get(started.getId()).getInvocations(),
          "the repeated delivery ran the handler again");

      // (d) a failing handler rolls the application's transaction back, and nothing the
      // handler changed is stored
      runner.reset();
      assertThrowsExactly(
          IllegalStateException.class,
          () -> dummyAdapter(context)
              .invokeTask(MODULE, PROCESS, delivery("failingTask", started.getId(), "job-2")));
      assertEquals(1, runner.rolledBack.get(), "the application's transaction was not rolled back");
      assertEquals(
          "processed",
          ApplicationOwnedStoresConfiguration.AGGREGATES.get(started.getId()).getStatus());

    }

  }

  @Test
  @DisplayName("Starting a workflow outside the application's unit of work is refused")
  public void startWithoutTheApplicationsTransactionIsRefused() throws IOException {

    ApplicationOwnedStoresConfiguration.reset();

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      final var aggregate = new AppTxAggregate();
      aggregate.setStatus("new");

      final var failure = assertThrowsExactly(
          IllegalStateException.class,
          () -> processService(context).startWorkflow(aggregate));

      assertTrue(failure.getMessage().contains("No transaction is active"), failure.getMessage());
      assertNull(aggregate.getId());
      assertTrue(ApplicationOwnedStoresConfiguration.SCHEDULED.isEmpty());

    }

  }

}
