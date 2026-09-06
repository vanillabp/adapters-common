package io.vanillabp.integration.test.activation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;

import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.deployment.DeploymentTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;

/**
 * Acceptance test on Spring Boot of what the identity of an activation buys: the dummy
 * adapter delivers the same task of the same workflow aggregate three times, once per
 * element of a multi-instance activity, and the handler correlates the same message name
 * with the same correlation id every time. All three correlations have to be planned.
 * <p>
 * Before this they shared one idempotency key - a called process is a secondary workflow
 * of the SAME aggregate, so module, process, aggregate id, message name and correlation
 * id were equal - and two of the three were discarded while the first one was still
 * waiting for its dispatch (see decision 23 in the repository's DECISIONS.md).
 * <p>
 * Pinned next to it: the guarantee this must not cost (a redelivery of ONE element is
 * still one correlation) and the case it deliberately does not fix (the same correlation
 * planned twice outside any activation).
 */
@ExtendWith(SuppressOutputExtension.class)
public class ActivationIdentityTest {

  private static final String MODULE = "activation-module";

  private static final String PROCESS = "ActivationProcess";

  private static final String ADAPTER = "test";

  private static final String AGGREGATE_ID = "4711";

  /**
   * The store, the persistence and the BPMN model the dummy adapter stands in for.
   */
  @Configuration
  static class ActivationConfiguration {

    static final Map<String, ActivationAggregate> AGGREGATES = new ConcurrentHashMap<>();

    static final List<PhaseTwoCall> PLANNED = new ArrayList<>();

    @Bean
    AggregatePersistenceAware<ActivationAggregate> activationPersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<ActivationAggregate> getAggregateClass() {
          return ActivationAggregate.class;
        }

        @Override
        public ActivationAggregate save(
            final ActivationAggregate aggregate) {
          AGGREGATES.put(aggregate.getId(), copyOf(aggregate));
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final ActivationAggregate aggregate) {
          return aggregate.getId();
        }

        @Override
        public Class<?> getAggregateIdType() {
          return String.class;
        }

        @Override
        public ActivationAggregate loadById(
            final Object aggregateId) {
          final var stored = AGGREGATES.get(aggregateId);
          return stored != null
              ? copyOf(stored)
              : null;
        }

      };

    }

    private static ActivationAggregate copyOf(
        final ActivationAggregate aggregate) {

      final var copy = new ActivationAggregate();
      copy.setId(aggregate.getId());
      copy.setCorrelations(aggregate.getCorrelations());
      return copy;

    }

    /**
     * A store of the application's own, deduplicating the calls which are still waiting
     * - nothing here dispatches, so every planned call keeps waiting and its key keeps
     * deduplicating. That is exactly the window the siblings used to collide in.
     */
    @Bean
    PhaseTwoOutbox activationOutbox() {

      return call -> {
        final var key = call.idempotencyKey().orElse(null);
        synchronized (PLANNED) {
          if ((key != null) && PLANNED
              .stream()
              .anyMatch(planned -> key.equals(planned.idempotencyKey().orElse(null)))) {
            return false;
          }
          PLANNED.add(call);
        }
        return true;
      };

    }

    /**
     * The workflow is where the correlation expects it to be, so the election has
     * something to elect.
     */
    @Bean
    DummyTaskAwarenessSource activationAwareness() {

      return new DummyTaskAwarenessSource() {

        @Override
        public WorkflowAwareness awarenessOfTask(
            final String adapterId,
            final Object workflowAggregateId,
            final String taskId) {
          return WorkflowAwareness.ACTIVE;
        }

        @Override
        public WorkflowAwareness awarenessOfWorkflow(
            final String adapterId,
            final Object workflowAggregateId) {
          return WorkflowAwareness.ACTIVE;
        }

      };

    }

    @Bean
    DataSource activationDataSource() {

      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();

    }

    @Bean
    PlatformTransactionManager transactionManager(
        final DataSource activationDataSource) {

      return new DataSourceTransactionManager(activationDataSource);

    }

    @Bean
    DummyTaskWiringSource activationTaskWiringSource() {

      return (
          adapterId,
          workflowModuleId,
          bpmnProcessId) -> PROCESS.equals(bpmnProcessId)
              ? List.of(new BpmnTaskSpec("Activity_RequestOffer", "requestOffer"))
              : List.of();

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
          activation-module:
            adapters:
              test:
                resources-location: classpath*:activation-module/processes
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
            ActivationWorkflowService.class,
            WorkflowModuleConfiguration.class,
            ActivationConfiguration.class,
            DeploymentTest.TestConfig.class)
        .run();

  }

  private SpringBootTestApplication buildTestApp() throws IOException {

    return SpringBootTestApplication.builder()
        .addResource("META-INF/workflow-module", MODULE)
        .addResource("application.yaml", APPLICATION_YAML)
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build();

  }

  /**
   * One delivery, as an adapter of a remote BPMS builds it. The delivery identity stays
   * the same while the BPMS repeats itself; the activation identity names the element
   * instance the BPMS is running.
   */
  private TaskInvocationContext delivery(
      final String deliveryId,
      final String activationId) {

    return new TaskInvocationContext() {

      @Override
      public String getAdapterId() {
        return ADAPTER;
      }

      @Override
      public String getTaskDefinition() {
        return "requestOffer";
      }

      @Override
      public String getWorkflowAggregateId() {
        return AGGREGATE_ID;
      }

      @Override
      public String getDeliveryId() {
        return deliveryId;
      }

      @Override
      public String getActivationId() {
        return activationId;
      }

    };

  }

  private void storeAggregate() {

    final var aggregate = new ActivationAggregate();
    aggregate.setId(AGGREGATE_ID);
    ActivationConfiguration.AGGREGATES.put(AGGREGATE_ID, aggregate);

  }

  private static List<String> plannedKeys() {

    synchronized (ActivationConfiguration.PLANNED) {
      return ActivationConfiguration.PLANNED
          .stream()
          .map(call -> call.idempotencyKey().orElse("<none>"))
          .toList();
    }

  }

  @Test
  public void siblingsOfOneAggregateEachReachTheBpms() throws IOException {

    ActivationConfiguration.AGGREGATES.clear();
    ActivationConfiguration.PLANNED.clear();

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      final var dummyAdapter = context.getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);
      storeAggregate();

      // three elements of a multi-instance call activity, three deliveries of the same
      // task of the same aggregate, one correlation each
      dummyAdapter.invokeTask(MODULE, PROCESS, delivery("job-1", "element-1"));
      dummyAdapter.invokeTask(MODULE, PROCESS, delivery("job-2", "element-2"));
      dummyAdapter.invokeTask(MODULE, PROCESS, delivery("job-3", "element-3"));

      final var keys = plannedKeys();
      Assertions.assertEquals(3, keys.size(), "all three siblings were planned: "
          + keys);
      Assertions
          .assertEquals(
              3,
              keys.stream().distinct().count(),
              "one key per activation: "
                  + keys);
      keys
          .forEach(key -> Assertions
              .assertTrue(
                  key
                      .startsWith(
                          "CORRELATE_MESSAGE|%s|%s|%s|OfferRequested|%s|element-".formatted(
                              MODULE,
                              PROCESS,
                              AGGREGATE_ID,
                              ActivationWorkflowService.CORRELATION_ID)),
                  key));

      // the guarantee this must not cost: the BPMS handing element 2 out again is not a
      // fourth element, so its correlation is the one already waiting
      dummyAdapter.invokeTask(MODULE, PROCESS, delivery("job-2", "element-2"));
      Assertions
          .assertEquals(
              3,
              plannedKeys().size(),
              "a redelivery of one element adds nothing: "
                  + plannedKeys());
    }

  }

  @Test
  public void aCorrelationOutsideAnyActivationKeepsTheKeyItAlwaysHad() throws IOException {

    ActivationConfiguration.AGGREGATES.clear();
    ActivationConfiguration.PLANNED.clear();

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      storeAggregate();

      @SuppressWarnings("unchecked")
      final var processService = (io.vanillabp.spi.process.ProcessService<ActivationAggregate>) context
          .getBean(io.vanillabp.spi.process.ProcessService.class);
      final var transactions = new org.springframework.transaction.support.TransactionTemplate(
          context.getBean(PlatformTransactionManager.class));

      // what a REST endpoint does: no activation, so no component naming one, and the
      // repetition is indistinguishable from a repeat of itself - which this story does
      // not fix and does not claim to
      transactions
          .executeWithoutResult(status -> processService
              .correlateMessage(
                  ActivationConfiguration.AGGREGATES.get(AGGREGATE_ID),
                  "OfferRequested",
                  ActivationWorkflowService.CORRELATION_ID));
      transactions
          .executeWithoutResult(status -> processService
              .correlateMessage(
                  ActivationConfiguration.AGGREGATES.get(AGGREGATE_ID),
                  "OfferRequested",
                  ActivationWorkflowService.CORRELATION_ID));

      final var keys = plannedKeys();
      Assertions.assertEquals(1, keys.size(), "the second one lost against the first: "
          + keys);
      Assertions
          .assertEquals(
              "CORRELATE_MESSAGE|%s|%s|%s|OfferRequested|%s".formatted(
                  MODULE,
                  PROCESS,
                  AGGREGATE_ID,
                  ActivationWorkflowService.CORRELATION_ID),
              keys.getFirst());
    }

  }

}
