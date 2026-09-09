package io.vanillabp.integration.test.secondary;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
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
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.process.WorkflowNotFoundException;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import lombok.Getter;
import lombok.Setter;

/**
 * What a called process delivered is found under the id the application calls, on Spring
 * Boot: this is the platform half of the change, and what it proves is that the ids a
 * workflow service serves really reach the two readers of the core.
 * <p>
 * The scenario is the everyday one. A workflow service serves {@code Ordering} and
 * declares {@code Shipping} as a secondary process, the BPMS hands out a task of
 * {@code Shipping}, and the application completes that task through the only process
 * service it ever sees, the one of {@code Ordering}. The delivery wrote two things under
 * {@code Shipping}: the record naming the adapter which delivered, and the hint saying
 * which BPMS holds this workflow. Both used to be invisible to the primary service, so
 * every operation on such a task paid a probe, no visibility window was ever waited out,
 * and the row of the task stayed open forever.
 */
@ExtendWith(SuppressOutputExtension.class)
public class SecondaryProcessDeliveryTest {

  private static final String MODULE = "test-module";

  private static final String PRIMARY_PROCESS = "Ordering";

  private static final String SECONDARY_PROCESS = "Shipping";

  private static final String ADAPTER = "test";

  private static final String AGGREGATE = "4711";

  @Getter
  @Setter
  public static class OrderAggregate {

    private String id;

    private String status;

  }

  /**
   * One workflow service, two BPMN processes: the one the application addresses and the
   * one it calls. The task of the called process is asynchronous, so the delivery leaves
   * it open and its record is the one a later completion is elected from.
   */
  @Service
  @WorkflowService(
      workflowAggregateClass = OrderAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = PRIMARY_PROCESS),
      secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = SECONDARY_PROCESS))
  public static class OrderWorkflowService {

    @WorkflowTask(taskDefinition = "orderTask")
    public void orderTask(
        final OrderAggregate aggregate) {

      aggregate.setStatus("ordered");

    }

    @WorkflowTask(taskDefinition = "awaitShipment")
    public void awaitShipment(
        final OrderAggregate aggregate,
        @TaskId final String taskId) {

      aggregate.setStatus("awaiting-shipment");

    }

  }

  @Configuration
  static class ScenarioConfiguration {

    static final Map<String, OrderAggregate> AGGREGATES = new ConcurrentHashMap<>();

    /**
     * What the BPMS is asked about a workflow, and how often. The count is the whole
     * point of the record: an operation answered from it asks nobody.
     */
    static final AtomicInteger PROBES = new AtomicInteger();

    /**
     * What the awareness probes answer while a test runs - "nobody knows this workflow"
     * unless a test says otherwise.
     */
    static volatile WorkflowAwareness AWARENESS = WorkflowAwareness.UNKNOWN_TO_BPMS;

    @Bean
    DummyTaskAwarenessSource countingAwarenessSource() {

      return (
          adapterId,
          workflowAggregateId,
          taskId) -> {
        PROBES.incrementAndGet();
        return AWARENESS;
      };

    }

    /**
     * The model of the file: two executable processes, one task each.
     */
    @Bean
    DummyTaskWiringSource orderingAndShippingWiringSource() {

      return new DummyTaskWiringSource() {

        @Override
        public List<String> executableProcessesOf(
            final String adapterId,
            final String workflowModuleId,
            final String filename) {

          return List.of(PRIMARY_PROCESS, SECONDARY_PROCESS);

        }

        @Override
        public List<BpmnTaskSpec> tasksOf(
            final String adapterId,
            final String workflowModuleId,
            final String bpmnProcessId) {

          return switch (bpmnProcessId) {
            case PRIMARY_PROCESS -> List.of(new BpmnTaskSpec("Activity_Order", "orderTask"));
            case SECONDARY_PROCESS -> List.of(new BpmnTaskSpec("Activity_AwaitShipment", "awaitShipment"));
            default -> List.of();
          };

        }

      };

    }

    @Bean
    AggregatePersistenceAware<OrderAggregate> orderPersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<OrderAggregate> getAggregateClass() {
          return OrderAggregate.class;
        }

        @Override
        public OrderAggregate save(
            final OrderAggregate aggregate) {
          AGGREGATES.put(aggregate.getId(), aggregate);
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final OrderAggregate aggregate) {
          return aggregate.getId();
        }

        @Override
        public Class<?> getAggregateIdType() {
          return String.class;
        }

        @Override
        public OrderAggregate loadById(
            final Object aggregateId) {
          return AGGREGATES.get(aggregateId);
        }

      };

    }

    @Bean
    DataSource secondaryProcessDataSource() {

      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();

    }

    @Bean
    PlatformTransactionManager transactionManager(
        final DataSource secondaryProcessDataSource) {

      return new DataSourceTransactionManager(secondaryProcessDataSource);

    }

  }

  private static final String APPLICATION_YAML = """
      vanillabp:
        prioritized-adapters:
          - test
        adapters:
          test:
            type: dummy
        workflow-modules:
          test-module:
            adapters:
              test:
                resources-location: classpath*:test-module/processes/secondary
      """;

  private SpringBootTestApplication buildTestApp() throws IOException {

    return SpringBootTestApplication
        .builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", APPLICATION_YAML)
        .addResource("test-module/processes/secondary/OrderingAndShipping.bpmn")
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
            TestPersistenceConfiguration.class,
            TestPhaseTwoOutboxConfiguration.class,
            WorkflowModuleConfiguration.class,
            ScenarioConfiguration.class,
            OrderWorkflowService.class,
            // after the data source it is conditional on: listed as a plain source, so
            // its conditions see the bean definitions registered before it
            JdbcTaskDeliveryLogAutoConfiguration.class,
            DeploymentTest.TestConfig.class)
        .run();

  }

  /**
   * A delivery of the called process, as its adapter reports it.
   */
  private TaskInvocationContext deliveryOfTheCalledProcess(
      final String taskId) {

    return new TaskInvocationContext() {

      @Override
      public String getAdapterId() {
        return ADAPTER;
      }

      @Override
      public String getTaskDefinition() {
        return "awaitShipment";
      }

      @Override
      public String getWorkflowAggregateId() {
        return AGGREGATE;
      }

      @Override
      public String getTaskId() {
        return taskId;
      }

      @Override
      public String getDeliveryId() {
        return "job-of-"
            + taskId;
      }

    };

  }

  @SuppressWarnings("unchecked")
  private ProcessService<OrderAggregate> primaryProcessService(
      final ConfigurableApplicationContext context) {

    return context.getBean(ProcessService.class);

  }

  /**
   * The transaction an application opens around its own call - VanillaBP writes the
   * aggregate and plans the operation in it.
   */
  private TransactionTemplate transaction(
      final ConfigurableApplicationContext context) {

    return new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

  }

  private void storeAggregate() {

    final var aggregate = new OrderAggregate();
    aggregate.setId(AGGREGATE);
    aggregate.setStatus("new");
    ScenarioConfiguration.AGGREGATES.put(AGGREGATE, aggregate);

  }

  /**
   * The moment the row of the given task carries, or <code>null</code> while the task is
   * open.
   */
  private Object taskClosedAt(
      final ConfigurableApplicationContext context,
      final String bpmnProcessId,
      final String taskId) {

    return new JdbcTemplate(context.getBean(DataSource.class))
        .queryForObject(
            """
                SELECT TASK_CLOSED_AT FROM %s \
                WHERE BPMN_PROCESS_ID = ? AND TASK_ID = ? AND AGGREGATE_ID = ?"""
                .formatted(JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME),
            Object.class,
            bpmnProcessId,
            taskId,
            AGGREGATE);

  }

  @Test
  @DisplayName("A task the called process delivered is completed on the primary service, and its row is closed")
  public void aTaskOfTheCalledProcessIsCompletedWithoutProbingAnybody() throws IOException {

    ScenarioConfiguration.AGGREGATES.clear();
    TestPhaseTwoOutboxConfiguration.clear();
    ScenarioConfiguration.AWARENESS = WorkflowAwareness.ACTIVE;

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      storeAggregate();
      final var bpms = context.getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);
      bpms.invokeTask(MODULE, SECONDARY_PROCESS, deliveryOfTheCalledProcess("shipment-1"));
      ScenarioConfiguration.PROBES.set(0);

      transaction(context)
          .executeWithoutResult(status -> primaryProcessService(context)
              .completeTask(ScenarioConfiguration.AGGREGATES.get(AGGREGATE), "shipment-1"));

      Assertions.assertEquals(
          0,
          ScenarioConfiguration.PROBES.get(),
          "the record of the called process answered the election, so no BPMS was asked");

      // and the moment the completion reaches the BPMS the row is closed - under the id
      // the delivery wrote it, which is the called process
      final var planned = TestPhaseTwoOutboxConfiguration.PLANNED.getLast();
      context.getBean(PhaseTwoRouter.class).dispatch(planned);

      Assertions.assertNotNull(
          taskClosedAt(context, SECONDARY_PROCESS, "shipment-1"),
          "the row nobody found used to stay open, counting as an open task for good");

    }

  }

  @Test
  @DisplayName("A workflow only the called process reported is planned rather than refused")
  public void theHintOfTheCalledProcessTellsAnUnknownWorkflowFromAWrongId() throws IOException {

    ScenarioConfiguration.AGGREGATES.clear();
    TestPhaseTwoOutboxConfiguration.clear();
    // the read model of the BPMS has not caught up: nobody reports this workflow
    ScenarioConfiguration.AWARENESS = WorkflowAwareness.UNKNOWN_TO_BPMS;

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      storeAggregate();
      final var processService = primaryProcessService(context);

      // an aggregate nobody ever delivered a task for: the id is simply wrong, and the
      // caller hears it at once
      final var unknown = new OrderAggregate();
      unknown.setId("no-workflow-of-this-one");
      unknown.setStatus("new");
      Assertions.assertThrows(
          WorkflowNotFoundException.class,
          () -> transaction(context)
              .executeWithoutResult(status -> processService.correlateMessage(unknown, "ShipmentArrived")));

      // the same answer from the BPMS, but this workflow was reported by a delivery of
      // the called process - so it exists, and the operation is planned
      final var bpms = context.getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);
      bpms.invokeTask(MODULE, SECONDARY_PROCESS, deliveryOfTheCalledProcess("shipment-2"));

      transaction(context)
          .executeWithoutResult(status -> processService
              .correlateMessage(ScenarioConfiguration.AGGREGATES.get(AGGREGATE), "ShipmentArrived"));

      Assertions.assertTrue(
          TestPhaseTwoOutboxConfiguration.PLANNED
              .stream()
              .anyMatch(call -> call.workflowAggregateId().equals(AGGREGATE)),
          "the hint the delivery wrote under the called process is what tells a workflow "
              + "which is not visible yet from an id nobody ever started");

    }

  }

}
