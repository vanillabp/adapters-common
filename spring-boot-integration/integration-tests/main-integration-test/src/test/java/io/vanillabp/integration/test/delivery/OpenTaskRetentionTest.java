package io.vanillabp.integration.test.delivery;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;

import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.delivery.JdbcTaskDeliveryLog;
import io.vanillabp.integration.delivery.JdbcTaskDeliveryLogAutoConfiguration;
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
 * Acceptance test on Spring Boot, with the default JDBC-based delivery log: the
 * record which answers the redeliveries of an OPEN task outlives the retention as long as
 * the BPMS keeps redelivering that task, while the record of a task nobody hands out any
 * more expires as it always did.
 * <p>
 * The second half is that nothing of this moves the age of the open task: it
 * keeps being measured from the moment the handler ran, so
 * <code>vanillabp.delivery.max-task-age</code> still fires. Both are asserted on records
 * backdated in the database rather than waited for.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OpenTaskRetentionTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "DeliveryProcess";

  private static final String ADAPTER = "test";

  /**
   * In-memory persistence of the test aggregate plus the H2 database the delivery log
   * writes its records into - the same shape the inbound-idempotency test uses.
   */
  @Configuration
  static class OpenTaskConfiguration {

    static final Map<String, DeliveryAggregate> AGGREGATES = new ConcurrentHashMap<>();

    @Bean
    AggregatePersistenceAware<DeliveryAggregate> deliveryPersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<DeliveryAggregate> getAggregateClass() {
          return DeliveryAggregate.class;
        }

        @Override
        public DeliveryAggregate save(
            final DeliveryAggregate aggregate) {
          AGGREGATES.put(aggregate.getId(), aggregate);
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final DeliveryAggregate aggregate) {
          return aggregate.getId();
        }

        @Override
        public Class<?> getAggregateIdType() {
          return String.class;
        }

        @Override
        public DeliveryAggregate loadById(
            final Object aggregateId) {
          return AGGREGATES.get(aggregateId);
        }

      };

    }

    @Bean
    DataSource deliveryDataSource() {

      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();

    }

    @Bean
    PlatformTransactionManager transactionManager(
        final DataSource deliveryDataSource) {

      return new DataSourceTransactionManager(deliveryDataSource);

    }

    @Bean
    DummyTaskWiringSource deliveryTaskWiringSource() {

      return (
          adapterId,
          workflowModuleId,
          bpmnProcessId) -> PROCESS.equals(bpmnProcessId)
              ? List
                  .of(
                      new BpmnTaskSpec("Activity_Process", "processTask"),
                      new BpmnTaskSpec("Activity_Error", "raiseBpmnError"),
                      new BpmnTaskSpec("Activity_Fail", "failTask"),
                      new BpmnTaskSpec("Activity_Undeduplicated", "undeduplicatedTask"),
                      new BpmnTaskSpec("Activity_Await", "awaitCompletion"),
                      new BpmnTaskSpec("Activity_Concurrent", "concurrentTask"))
              : List.of();

    }

  }

  /**
   * An hour of retention and an hour of maximum age: the records are backdated by two, so
   * both boundaries are crossed without waiting for anything.
   */
  private static final String APPLICATION_YAML = """
      vanillabp:
        outbox:
          retention: PT1H
        delivery:
          max-task-age: PT1H
        adapters:
          test:
            type: dummy
            test: 1
        workflow-modules:
          test-module:
            adapters:
              test:
                resources-location: classpath*:test-module/processes/delivery
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
            DeliveryWorkflowService.class,
            WorkflowModuleConfiguration.class,
            OpenTaskConfiguration.class,
            JdbcTaskDeliveryLogAutoConfiguration.class,
            DeploymentTest.TestConfig.class)
        .run();

  }

  private SpringBootTestApplication buildTestApp() throws IOException {

    return SpringBootTestApplication.builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", APPLICATION_YAML)
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build();

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
      public String getTaskId() {
        return deliveryId;
      }

      @Override
      public String getDeliveryId() {
        return deliveryId;
      }

    };

  }

  private void storeAggregate(
      final String id) {

    final var aggregate = new DeliveryAggregate();
    aggregate.setId(id);
    aggregate.setStatus("new");
    OpenTaskConfiguration.AGGREGATES.put(id, aggregate);

  }

  private JdbcTemplate jdbc(
      final ConfigurableApplicationContext context) {

    return new JdbcTemplate(context.getBean(DataSource.class));

  }

  private int recordCount(
      final ConfigurableApplicationContext context,
      final String aggregateId) {

    return jdbc(context)
        .queryForObject(
            "SELECT COUNT(*) FROM %s WHERE AGGREGATE_ID = ?".formatted(JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME),
            Integer.class,
            aggregateId);

  }

  /**
   * Moves every record of the table back in time, both of its timestamps - a database two
   * hours older than the application which reads it.
   */
  private void backdateEveryRecordBy(
      final ConfigurableApplicationContext context,
      final Duration age) {

    final var moment = Timestamp.from(Instant.now().minus(age));
    jdbc(context)
        .update(
            "UPDATE %s SET RECORDED_AT = ?, LAST_SEEN_AT = ?"
                .formatted(JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME),
            moment,
            moment);

  }

  private Timestamp lastSeenAt(
      final ConfigurableApplicationContext context,
      final String aggregateId) {

    return jdbc(context)
        .queryForObject(
            "SELECT LAST_SEEN_AT FROM %s WHERE AGGREGATE_ID = ?"
                .formatted(JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME),
            Timestamp.class,
            aggregateId);

  }

  @Test
  @DisplayName("The record of an open task survives the retention, the record of a finished one does not")
  public void theRecordOfAnOpenTaskSurvivesTheRetention() throws IOException {

    OpenTaskConfiguration.AGGREGATES.clear();

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      final var dummyAdapter = context.getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);
      final var deliveryLog = context.getBean(JdbcTaskDeliveryLog.class);

      // an asynchronous task the application never completes, and an ordinary one which is
      // done the moment its handler returns
      storeAggregate("4711");
      storeAggregate("4712");
      final var opened = dummyAdapter.invokeTask(MODULE, PROCESS, delivery("awaitCompletion", "4711", "job-1"));
      dummyAdapter.invokeTask(MODULE, PROCESS, delivery("processTask", "4712", "job-2"));
      Assertions.assertEquals(WorkflowTaskOutcome.Kind.COMPLETION_PENDING, opened.kind());
      Assertions.assertEquals(1, recordCount(context, "4711"));
      Assertions.assertEquals(1, recordCount(context, "4712"));

      // two hours later, an hour past the retention and past the maximum age
      backdateEveryRecordBy(context, Duration.ofHours(2));
      final var backdated = lastSeenAt(context, "4711");

      // the BPMS renews the lock of the task nobody completed, which is a redelivery
      final var redelivered = dummyAdapter.invokeTask(MODULE, PROCESS, delivery("awaitCompletion", "4711", "job-1"));
      Assertions.assertEquals(WorkflowTaskOutcome.Kind.COMPLETION_PENDING, redelivered.kind());
      Assertions
          .assertEquals(
              1,
              OpenTaskConfiguration.AGGREGATES.get("4711").getInvocations(),
              "the redelivery is answered from the record, the handler ran once");
      Assertions
          .assertTrue(
              redelivered.openFor().compareTo(Duration.ofHours(2)) >= 0,
              "the age is still measured from the moment the handler ran: "
                  + redelivered.openFor());
      Assertions
          .assertTrue(
              redelivered.maxAgeExceeded(),
              "so a task open longer than 'vanillabp.delivery.max-task-age' is still reported");

      // the cleanup writes what the redelivery collected and then deletes what nobody saw
      final var deleted = deliveryLog.cleanUpExpiredRecords();

      Assertions.assertEquals(1, deleted);
      Assertions
          .assertEquals(
              1,
              recordCount(context, "4711"),
              "the task is still being redelivered, so the record answering it stays");
      Assertions
          .assertEquals(
              0,
              recordCount(context, "4712"),
              "nobody redelivers a task which is done - its record expires as it always did");
      Assertions
          .assertTrue(
              lastSeenAt(context, "4711").after(backdated),
              "the moment the record was last seen moved forward");

      // and the record kept alive still does its job
      dummyAdapter.invokeTask(MODULE, PROCESS, delivery("awaitCompletion", "4711", "job-1"));
      Assertions
          .assertEquals(
              1,
              OpenTaskConfiguration.AGGREGATES.get("4711").getInvocations(),
              "which is the whole point: the handler of an open task must not run twice");

    }

  }

}
