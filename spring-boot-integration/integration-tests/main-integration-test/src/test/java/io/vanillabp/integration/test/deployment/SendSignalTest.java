package io.vanillabp.integration.test.deployment;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;

import io.vanillabp.bpmsdouble.DummyPhaseTwoListener;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.sample.Aggregate;
import io.vanillabp.integration.test.sample.SampleWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.spi.process.ProcessService;

/**
 * Acceptance test of broadcasting a BPMN signal with the dummy adapter
 * standing in for a BPMS. Two adapter ids are configured, one of them named at the
 * WORKFLOW level only - the deployment union. What the test pins: the broadcast
 * reaches BOTH of them, because during a migration the workflows waiting for the
 * signal are spread across the BPMS, and each of them gets an outbox entry of its
 * own.
 */
@ExtendWith(SuppressOutputExtension.class)
public class SendSignalTest {

  /**
   * Records what the dummy adapter broadcast.
   */
  static class RecordingSignals implements DummyPhaseTwoListener {

    final List<String> broadcast = new LinkedList<>();

    @Override
    public void startedWorkflowPhaseTwo(
        final Object workflowAggregateId) {

    }

    @Override
    public void broadcastSignal(
        final String signalName,
        final boolean phaseTwo) {

      broadcast.add("%s/%s".formatted(signalName, phaseTwo
          ? "phase-two"
          : "phase-one"));

    }

  }

  @Configuration
  static class SignalConfiguration {

    @Bean
    javax.sql.DataSource signalDataSource() {

      return new org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder()
          .setType(org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();

    }

    @Bean
    org.springframework.transaction.PlatformTransactionManager transactionManager(
        final javax.sql.DataSource signalDataSource) {

      return new org.springframework.jdbc.datasource.DataSourceTransactionManager(signalDataSource);

    }

    @Bean
    org.springframework.transaction.support.TransactionTemplate transactionTemplate(
        final org.springframework.transaction.PlatformTransactionManager transactionManager) {

      return new org.springframework.transaction.support.TransactionTemplate(transactionManager);

    }

    @Bean
    RecordingSignals recordingSignals() {

      return new RecordingSignals();

    }

    @Bean
    AggregatePersistenceAware<Aggregate> sampleAggregatePersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<Aggregate> getAggregateClass() {
          return Aggregate.class;
        }

        @Override
        public Class<?> getAggregateIdType() {
          return String.class;
        }

      };

    }

  }

  /**
   * The module prioritizes 'test', while one workflow of it uses 'test2' - so
   * 'test2' belongs to the deployment union without being prioritized for
   * 'DummyProcess'.
   */
  private static final String APPLICATION_YAML = """
      vanillabp:
        prioritized-adapters:
          - test
          - test2
        adapters:
          test:
            type: dummy
          test2:
            type: dummy
        workflow-modules:
          test-module:
            prioritized-adapters:
              - test
            adapters:
              test:
                resources-location: classpath*:test-module/processes/dummy
              test2:
                resources-location: classpath*:test-module/processes/dummy
            workflows:
              SomeOtherProcess:
                prioritized-adapters:
                  - test2
      """;

  private ConfigurableApplicationContext runTestApplication(
      final SpringBootTestApplication testApp) {

    return testApp
        .applicationBuilder(
            DummyAdapterConfiguration.class,
            DummyAdapterProcessServiceConfiguration.class,
            WorkflowModuleAutoConfiguration.class,
            SpringBootMigrationAdapterAutoConfiguration.class,
            TestPersistenceConfiguration.class, TestPhaseTwoOutboxConfiguration.class,
            SampleWorkflowService.class,
            WorkflowModuleConfiguration.class,
            SignalConfiguration.class)
        .run();

  }

  @SuppressWarnings("unchecked")
  @Test
  public void aSignalReachesEveryDeployedBpms() throws IOException {

    try (var testApp = SpringBootTestApplication
        .builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", APPLICATION_YAML)
        .addResource("test-module/processes/dummy/DummyProcess.bpmn")
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build(); var context = runTestApplication(testApp)) {

      final var processService = (ProcessService<Aggregate>) context
          .getBeanProvider(
              ResolvableType.forClassWithGenerics(ProcessService.class, Aggregate.class))
          .getObject();

      TestPhaseTwoOutboxConfiguration.clear();
      context
          .getBean(org.springframework.transaction.support.TransactionTemplate.class)
          .executeWithoutResult(status -> processService.sendSignal("OrderReceived"));

      // both adapter ids of the module are asked, and each of them gets an entry of
      // its own: the broadcast leaves after the commit, never inside the transaction
      Assertions
          .assertEquals(
              List.of("test", "test2"),
              TestPhaseTwoOutboxConfiguration.PLANNED
                  .stream()
                  .map(io.vanillabp.integration.spi.PhaseTwoCall::adapterId)
                  .toList(),
              "the signal has to reach every BPMS the workflow module is deployed to");
      Assertions.assertTrue(context.getBean(RecordingSignals.class).broadcast.isEmpty());

    }

  }

  @Test
  public void aSignalWithoutATransactionFailsGuiding() throws IOException {

    try (var testApp = SpringBootTestApplication
        .builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", APPLICATION_YAML)
        .addResource("test-module/processes/dummy/DummyProcess.bpmn")
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build(); var context = runTestApplication(testApp)) {

      @SuppressWarnings("unchecked")
      final var processService = (ProcessService<Aggregate>) context
          .getBeanProvider(
              ResolvableType.forClassWithGenerics(ProcessService.class, Aggregate.class))
          .getObject();

      final var exception = Assertions
          .assertThrows(
              IllegalStateException.class,
              () -> processService.sendSignal("OrderReceived"));

      // the message says why a broadcast needs a transaction of its own accord
      Assertions.assertTrue(exception.getMessage().contains("Broadcasting a signal"), exception.getMessage());
      Assertions.assertTrue(exception.getMessage().contains("@Transactional"), exception.getMessage());

    }

  }

}
