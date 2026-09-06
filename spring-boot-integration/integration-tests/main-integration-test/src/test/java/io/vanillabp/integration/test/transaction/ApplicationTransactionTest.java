package io.vanillabp.integration.test.transaction;

import java.io.IOException;
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
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.deployment.DeploymentTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;

/**
 * Acceptance test of the transaction-contract safeguards on Spring Boot: a
 * transaction annotation of the application in the call chain of a
 * <code>&#64;WorkflowTask</code> handler marks VanillaBP's transaction rollback-only,
 * and neither the aggregate changes nor the state of the BPMS can be committed
 * afterwards. Since the mark cannot be cleared, VanillaBP turns the silent data loss
 * into a failure whose message names the task and the remedy.
 * <p>
 * The startup check covering an annotation ON the handler needs a workflow service
 * class that fails EVERY boot of its Maven module, so it lives in its own module
 * ({@code transaction-annotation-integration-test}); the annotation matrix itself is
 * covered by the core's unit tests. What is proven here is the accepted counterpart
 * ({@code noRollbackFor}, which has to boot and to work) and the runtime check, in
 * both transaction shapes.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ApplicationTransactionTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TransactionProcess";

  private static final String APPLICATION_YAML = """
      vanillabp:
        adapters:
          test:
            type: dummy
        workflow-modules:
          test-module:
            adapters:
              test:
                resources-location: classpath*:test-module/processes/transaction
      """;

  @Configuration
  @EnableTransactionManagement
  static class TransactionTestConfiguration {

    static final Map<String, TransactionAggregate> AGGREGATES = new ConcurrentHashMap<>();

    @Bean
    NestedTransactionalBean nestedTransactionalBean() {

      return new NestedTransactionalBean();

    }

    @Bean
    TransactionWorkflowService transactionWorkflowService() {

      return new TransactionWorkflowService();

    }

    @Bean
    AggregatePersistenceAware<TransactionAggregate> transactionPersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<TransactionAggregate> getAggregateClass() {
          return TransactionAggregate.class;
        }

        @Override
        public TransactionAggregate save(
            final TransactionAggregate aggregate) {
          AGGREGATES.put(aggregate.getId(), copyOf(aggregate));
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final TransactionAggregate aggregate) {
          return aggregate.getId();
        }

        @Override
        public Class<?> getAggregateIdType() {
          return String.class;
        }

        @Override
        public TransactionAggregate loadById(
            final Object aggregateId) {
          final var stored = AGGREGATES.get(aggregateId);
          return stored != null
              ? copyOf(stored)
              : null;
        }

      };

    }

    private static TransactionAggregate copyOf(
        final TransactionAggregate aggregate) {

      final var copy = new TransactionAggregate();
      copy.setId(aggregate.getId());
      copy.setStatus(aggregate.getStatus());
      return copy;

    }

    @Bean
    DataSource transactionDataSource() {

      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();

    }

    @Bean
    PlatformTransactionManager transactionManager(
        final DataSource transactionDataSource) {

      return new DataSourceTransactionManager(transactionDataSource);

    }

    @Bean
    DummyTaskWiringSource transactionTaskWiringSource() {

      return (
          adapterId,
          workflowModuleId,
          bpmnProcessId) -> PROCESS.equals(bpmnProcessId)
              ? List.of(
                  new BpmnTaskSpec("Activity_Nested", "nestedTaskException"),
                  new BpmnTaskSpec("Activity_Swallowed", "swallowedNestedFailure"),
                  new BpmnTaskSpec("Activity_Accepted", "acceptedAnnotation"))
              : List.of();

    }

  }

  private SpringBootTestApplication buildTestApp() throws IOException {

    return SpringBootTestApplication.builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", APPLICATION_YAML)
        // the dummy adapter derives the BPMN process id from the file name
        .addResource("test-module/processes/transaction/TransactionProcess.bpmn", "<definitions/>")
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build();

  }

  private ConfigurableApplicationContext runTestApplication(
      final SpringBootTestApplication testApp) {

    return testApp
        .applicationBuilder(
            DummyAdapterConfiguration.class,
            DummyAdapterProcessServiceConfiguration.class,
            WorkflowModuleAutoConfiguration.class,
            SpringBootMigrationAdapterAutoConfiguration.class,
            TestPersistenceConfiguration.class, TestPhaseTwoOutboxConfiguration.class,
            WorkflowModuleConfiguration.class,
            TransactionTestConfiguration.class,
            DeploymentTest.TestConfig.class)
        .run();

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

  private TransactionAggregate storeAggregate(
      final String id) {

    final var aggregate = new TransactionAggregate();
    aggregate.setId(id);
    aggregate.setStatus("new");
    TransactionTestConfiguration.AGGREGATES.put(id, aggregate);
    return aggregate;

  }

  private void assertGuidingMessage(
      final IllegalStateException failure,
      final String taskDefinition) {

    Assertions.assertTrue(
        failure.getMessage().contains("marked rollback-only"),
        "unexpected message: "
            + failure.getMessage());
    Assertions.assertTrue(
        failure.getMessage().contains(taskDefinition),
        "unexpected message: "
            + failure.getMessage());
    Assertions.assertTrue(
        failure.getMessage().contains(PROCESS) && failure.getMessage().contains(MODULE),
        "unexpected message: "
            + failure.getMessage());
    // the rollback rules as they are written on THIS platform: Spring Boot honors its
    // own annotation AND the JTA one, so both attributes are legitimate options here
    Assertions.assertTrue(
        failure.getMessage().contains("noRollbackFor = TaskException.class") && failure.getMessage()
            .contains("dontRollbackOn = TaskException.class"),
        "unexpected message: "
            + failure.getMessage());

  }

  @Test
  public void aTransactionAnnotationInTheCallChainFailsTheTaskInsteadOfLosingTheChanges() throws IOException {

    TransactionTestConfiguration.AGGREGATES.clear();

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      final var dummyAdapter = context.getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);

      // (a) the shape of a remote BPMS: VanillaBP opens its own transaction, the
      // nested bean joins it and takes it down with the TaskException
      storeAggregate("5001");
      final var ownTransaction = Assertions.assertThrows(
          IllegalStateException.class,
          () -> dummyAdapter.invokeTask(MODULE, PROCESS, context("nestedTaskException", "5001", false)));
      assertGuidingMessage(ownTransaction, "nestedTaskException");

      // (b) the shape of an embedded engine: VanillaBP participates in the
      // caller's transaction, where the mark is the silent case - the commit of a
      // participating transaction returns normally although nothing is committed
      storeAggregate("5002");
      final var transactionTemplate = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
      final var joinedTransaction = Assertions.assertThrows(
          IllegalStateException.class,
          () -> transactionTemplate.execute(
              status -> dummyAdapter.invokeTask(MODULE, PROCESS, context("nestedTaskException", "5002", true))));
      assertGuidingMessage(joinedTransaction, "nestedTaskException");

      // (c) no TaskException involved at all: the handler swallowed the exception
      // of the nested call and returned normally, which would report the task as
      // completed while nothing can be persisted
      storeAggregate("5003");
      final var swallowed = Assertions.assertThrows(
          IllegalStateException.class,
          () -> dummyAdapter.invokeTask(MODULE, PROCESS, context("swallowedNestedFailure", "5003", false)));
      assertGuidingMessage(swallowed, "swallowedNestedFailure");

      // (d) the annotation a version-1 application carries: accepted by the
      // startup check (this context booted with it) and working at runtime
      storeAggregate("5004");
      final var accepted = dummyAdapter.invokeTask(MODULE, PROCESS, context("acceptedAnnotation", "5004", false));
      Assertions.assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, accepted.kind());
      Assertions.assertEquals(
          "accepted",
          TransactionTestConfiguration.AGGREGATES.get("5004").getStatus());

    }

  }

}
