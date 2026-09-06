package io.vanillabp.integration.test.delivery;

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
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
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
import io.vanillabp.spi.service.WorkflowEnd;

/**
 * Acceptance test of the release of delivery records on Spring Boot, with the
 * default JDBC-based delivery log: a workflow which ended deletes the records of its
 * processed deliveries instead of leaving them to the retention. The workflow service of
 * this test has NO <code>&#64;WorkflowEnded</code> method on purpose - the notification is
 * asked for by the release alone, which is what makes the feature work without touching
 * any adapter.
 * <p>
 * Pinned as well: the record of a SECOND workflow on the same aggregate, written after the
 * end of the first one, survives; records of another aggregate stay; and with the option
 * off nothing is released and no end listener is attached at all.
 */
@ExtendWith(SuppressOutputExtension.class)
public class DeliveryRecordReleaseTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "DeliveryProcess";

  private static final String ADAPTER = "test";

  /**
   * The same in-memory persistence plus H2 the inbound-idempotency test uses - the records
   * are really written, read and deleted through JDBC.
   */
  @Configuration
  static class ReleaseConfiguration {

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
          AGGREGATES.put(aggregate.getId(), copyOf(aggregate));
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
          final var stored = AGGREGATES.get(aggregateId);
          return stored != null
              ? copyOf(stored)
              : null;
        }

      };

    }

    private static DeliveryAggregate copyOf(
        final DeliveryAggregate aggregate) {

      final var copy = new DeliveryAggregate();
      copy.setId(aggregate.getId());
      copy.setStatus(aggregate.getStatus());
      copy.setInvocations(aggregate.getInvocations());
      return copy;

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

    /**
     * Stands in for the BPMN model, so the wiring validation passes.
     */
    @Bean
    DummyTaskWiringSource deliveryTaskWiringSource() {

      return (
          adapterId,
          workflowModuleId,
          bpmnProcessId) -> PROCESS.equals(bpmnProcessId)
              ? List.of(
                  new BpmnTaskSpec("Activity_Process", "processTask"),
                  new BpmnTaskSpec("Activity_Error", "raiseBpmnError"),
                  new BpmnTaskSpec("Activity_Fail", "failTask"),
                  new BpmnTaskSpec("Activity_Undeduplicated", "undeduplicatedTask"),
                  new BpmnTaskSpec("Activity_Await", "awaitCompletion"),
                  new BpmnTaskSpec("Activity_Concurrent", "concurrentTask"))
              : List.of();

    }

  }

  private static final String APPLICATION_YAML = """
      vanillabp:
        adapters:
          test:
            type: dummy
            test: 1
        delivery:
          release-on-workflow-end: %s
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
            ReleaseConfiguration.class,
            // after the data source it is conditional on: the auto-configuration is
            // listed as a plain source here, so its conditions see the bean definitions
            // registered before it
            JdbcTaskDeliveryLogAutoConfiguration.class,
            DeploymentTest.TestConfig.class)
        .run();

  }

  private SpringBootTestApplication buildTestApp(
      final boolean releaseOnWorkflowEnd) throws IOException {

    return SpringBootTestApplication.builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", APPLICATION_YAML.formatted(releaseOnWorkflowEnd))
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
      public String getDeliveryId() {
        return deliveryId;
      }

    };

  }

  /**
   * The end of a workflow, as an adapter's process-end listener reports it.
   */
  private WorkflowEndedContext workflowEnded(
      final String aggregateId) {

    return new WorkflowEndedContext() {

      @Override
      public String getWorkflowAggregateId() {
        return aggregateId;
      }

      @Override
      public WorkflowEnd.Kind getKind() {
        return WorkflowEnd.Kind.COMPLETED;
      }

      @Override
      public java.time.Instant getEndTime() {
        return java.time.Instant.now();
      }

      @Override
      public String getEndEventId() {
        return "Event_Done";
      }

    };

  }

  private void storeAggregate(
      final String id) {

    final var aggregate = new DeliveryAggregate();
    aggregate.setId(id);
    aggregate.setStatus("new");
    ReleaseConfiguration.AGGREGATES.put(id, aggregate);

  }

  private int recordCount(
      final ConfigurableApplicationContext context,
      final String aggregateId) {

    return new JdbcTemplate(context.getBean(DataSource.class))
        .queryForObject(
            "SELECT COUNT(*) FROM %s WHERE AGGREGATE_ID = ?".formatted(JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME),
            Integer.class,
            aggregateId);

  }

  @Test
  public void anEndedWorkflowReleasesItsRecords() throws IOException {

    ReleaseConfiguration.AGGREGATES.clear();

    try (var testApp = buildTestApp(true); var context = runTestApplication(testApp)) {

      final var dummyAdapter = context.getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);
      Assertions.assertEquals(
          List.of(PROCESS),
          dummyAdapter.getProcessesWithEndListener(),
          "the release needs the end of a workflow, so the listener is attached without a @WorkflowEnded method");

      storeAggregate("4711");
      storeAggregate("4712");
      dummyAdapter.invokeTask(MODULE, PROCESS, delivery("processTask", "4711", "job-1"));
      dummyAdapter.invokeTask(MODULE, PROCESS, delivery("processTask", "4711", "job-2"));
      dummyAdapter.invokeTask(MODULE, PROCESS, delivery("processTask", "4712", "job-3"));
      Assertions.assertEquals(2, recordCount(context, "4711"));
      Assertions.assertEquals(1, recordCount(context, "4712"));

      dummyAdapter.notifyWorkflowEnded(MODULE, PROCESS, workflowEnded("4711"));

      Assertions.assertEquals(0, recordCount(context, "4711"), "the ended workflow releases its records");
      Assertions.assertEquals(1, recordCount(context, "4712"), "another workflow keeps its own");

      // a SECOND workflow on the same aggregate: its delivery is processed after the end
      // of the first one, so its record was written after the notification and stays
      dummyAdapter.invokeTask(MODULE, PROCESS, delivery("processTask", "4711", "job-4"));
      Assertions.assertEquals(1, recordCount(context, "4711"));

      // a repeated delivery of the second workflow is still answered from that record
      final var repeated = dummyAdapter.invokeTask(MODULE, PROCESS, delivery("processTask", "4711", "job-4"));
      Assertions.assertEquals(
          io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome.Kind.COMPLETED,
          repeated.kind());
      Assertions.assertEquals(
          3,
          ReleaseConfiguration.AGGREGATES.get("4711").getInvocations(),
          "the released records must not make a repeated delivery run the handler again");

    }

  }

  @Test
  public void withoutTheOptionTheRecordsStayAndNoListenerIsAttached() throws IOException {

    ReleaseConfiguration.AGGREGATES.clear();

    try (var testApp = buildTestApp(false); var context = runTestApplication(testApp)) {

      final var dummyAdapter = context.getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);
      Assertions.assertEquals(
          List.of(),
          dummyAdapter.getProcessesWithEndListener(),
          "a model must not pay for a listener nobody uses");

      storeAggregate("4713");
      dummyAdapter.invokeTask(MODULE, PROCESS, delivery("processTask", "4713", "job-5"));
      Assertions.assertEquals(1, recordCount(context, "4713"));

      // a BPMS reporting the end anyway (a listener of another feature) changes nothing
      dummyAdapter.notifyWorkflowEnded(MODULE, PROCESS, workflowEnded("4713"));

      Assertions.assertEquals(
          1,
          recordCount(context, "4713"),
          "with the option off the retention is what cleans up");

    }

  }

}
