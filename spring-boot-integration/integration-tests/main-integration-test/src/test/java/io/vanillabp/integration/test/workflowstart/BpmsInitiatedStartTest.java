package io.vanillabp.integration.test.workflowstart;

import java.io.IOException;
import java.time.Instant;
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

import io.vanillabp.bpmsdouble.DummyBpmsInitiatedStartSource;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.deployment.DeploymentTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.spi.service.BpmsStartTrigger;
import io.vanillabp.spi.service.WorkflowEnd;

/**
 * Acceptance test of workflows the BPMS starts on its own, with the dummy
 * adapter standing in for a BPMS reporting a timer, signal or conditional start. It
 * covers what an application gets without writing a line of code (the aggregate is
 * built, the timer's trigger time is its ID, the process variables land in it), that
 * a repeated notification creates nothing twice, and what an optional
 * <code>&#64;WorkflowStartedByBpms</code> method adds on top.
 */
@ExtendWith(SuppressOutputExtension.class)
public class BpmsInitiatedStartTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TimerProcess";

  private static final String TIMER_EVENT = "DailyTimer";

  private static final String SIGNAL_EVENT = "SignalStart";

  private static final Instant TRIGGER_TIME = Instant.parse("2026-08-12T04:00:00Z");

  @Configuration
  static class WorkflowStartConfiguration {

    static final Map<String, WorkflowStartAggregate> AGGREGATES = new ConcurrentHashMap<>();

    @Bean
    AggregatePersistenceAware<WorkflowStartAggregate> workflowStartPersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<WorkflowStartAggregate> getAggregateClass() {
          return WorkflowStartAggregate.class;
        }

        @Override
        public String getAggregateIdName() {
          return "id";
        }

        @Override
        public Class<?> getAggregateIdType() {
          return String.class;
        }

        @Override
        public Object getAggregateId(
            final WorkflowStartAggregate aggregate) {
          return aggregate.getId();
        }

        @Override
        public WorkflowStartAggregate save(
            final WorkflowStartAggregate aggregate) {
          AGGREGATES.put(aggregate.getId(), copyOf(aggregate));
          return aggregate;
        }

        @Override
        public WorkflowStartAggregate loadById(
            final Object aggregateId) {
          final var stored = AGGREGATES.get(aggregateId);
          return stored != null
              ? copyOf(stored)
              : null;
        }

      };

    }

    private static WorkflowStartAggregate copyOf(
        final WorkflowStartAggregate aggregate) {

      final var copy = new WorkflowStartAggregate();
      copy.setId(aggregate.getId());
      copy.setRegion(aggregate.getRegion());
      copy.setAmount(aggregate.getAmount());
      copy.setStartedBy(aggregate.getStartedBy());
      return copy;

    }

    @Bean
    DataSource workflowStartDataSource() {

      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();

    }

    @Bean
    PlatformTransactionManager transactionManager(
        final DataSource workflowStartDataSource) {

      return new DataSourceTransactionManager(workflowStartDataSource);

    }

  }

  /**
   * Stands in for the BPMN model: 'TimerProcess' has a timer and a signal start
   * event, every other process of the module has none.
   */
  @Configuration
  static class StartEventsConfiguration {

    @Bean
    DummyBpmsInitiatedStartSource bpmsInitiatedStartSource() {

      return (
          adapterId,
          workflowModuleId,
          bpmnProcessId) -> PROCESS.equals(bpmnProcessId)
              ? List
                  .of(
                      BpmsInitiatedStartSpec.of(TIMER_EVENT, BpmsStartTrigger.Kind.TIMER),
                      new BpmsInitiatedStartSpec(
                          SIGNAL_EVENT, BpmsStartTrigger.Kind.SIGNAL, "OrderReceived", null))
              : List.of();

    }

  }

  /**
   * The same application, but its BPMN model has no start event the BPMS fires on
   * its own - which makes the workflow service's method unservable.
   */
  @Configuration
  static class NoStartEventConfiguration {

    @Bean
    DummyBpmsInitiatedStartSource emptyBpmsInitiatedStartSource() {

      return (
          adapterId,
          workflowModuleId,
          bpmnProcessId) -> List.of();

    }

  }

  private static final String APPLICATION_YAML = """
      vanillabp:
        adapters:
          test:
            type: dummy
            test: 1
        workflow-modules:
          test-module:
            adapters:
              test:
                resources-location: classpath*:test-module/processes/workflowstart
      """;

  private ConfigurableApplicationContext runTestApplication(
      final SpringBootTestApplication testApp,
      final Class<?>... additionalClasses) {

    final var classes = new java.util.LinkedList<Class<?>>(
        List
            .of(
                DummyAdapterConfiguration.class,
                DummyAdapterProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class,
                TestPersistenceConfiguration.class, TestPhaseTwoOutboxConfiguration.class,
                WorkflowStartWorkflowService.class,
                WorkflowModuleConfiguration.class,
                DeploymentTest.TestConfig.class));
    classes.addAll(List.of(additionalClasses));
    return testApp.applicationBuilder(classes.toArray(Class[]::new)).run();

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

  private BpmsInitiatedStartContext context(
      final BpmsStartTrigger.Kind kind,
      final String startEventId,
      final Map<String, Object> variables) {

    return new BpmsInitiatedStartContext() {

      @Override
      public String getStartEventId() {
        return startEventId;
      }

      @Override
      public BpmsStartTrigger.Kind getKind() {
        return kind;
      }

      @Override
      public Instant getStartInstant() {
        return TRIGGER_TIME;
      }

      @Override
      public String getSignalName() {
        return kind == BpmsStartTrigger.Kind.SIGNAL
            ? "OrderReceived"
            : null;
      }

      @Override
      public Map<String, Object> getVariables() {
        return variables;
      }

    };

  }

  @Test
  public void bpmsInitiatedStartsBuildTheirAggregate() throws IOException {

    WorkflowStartConfiguration.AGGREGATES.clear();

    try (var testApp = buildTestApp(); var context = runTestApplication(
        testApp,
        WorkflowStartConfiguration.class,
        StartEventsConfiguration.class)) {

      final var dummyAdapter = context
          .getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);

      // (a) a timer start without any application code: the aggregate is built, the
      // trigger time is its ID and the variables the model set land in it
      final var timerStart = dummyAdapter
          .startWorkflowByBpms(
              MODULE,
              PROCESS,
              context(
                  BpmsStartTrigger.Kind.TIMER,
                  TIMER_EVENT,
                  Map.of("region", "north", "amount", 42, "notModelled", "ignored")));

      Assertions.assertTrue(timerStart.created());
      Assertions.assertEquals(TRIGGER_TIME.toString(), timerStart.workflowAggregateId());
      Assertions.assertEquals("id", timerStart.workflowAggregateIdName());
      Assertions
          .assertEquals(TRIGGER_TIME.toString(), timerStart.variables().get("id"));

      final var timerAggregate = WorkflowStartConfiguration.AGGREGATES.get(TRIGGER_TIME.toString());
      Assertions.assertNotNull(timerAggregate);
      Assertions.assertEquals("north", timerAggregate.getRegion());
      Assertions.assertEquals(42, timerAggregate.getAmount());
      // the method of the workflow service serves the SIGNAL start event only
      Assertions.assertNull(timerAggregate.getStartedBy());

      // (b) the same timer time reported again (a retried notification): nothing is
      // created twice and business data written meanwhile survives
      timerAggregate.setRegion("changed meanwhile");
      WorkflowStartConfiguration.AGGREGATES.put(TRIGGER_TIME.toString(), timerAggregate);
      final var repeated = dummyAdapter
          .startWorkflowByBpms(
              MODULE, PROCESS, context(BpmsStartTrigger.Kind.TIMER, TIMER_EVENT, Map.of("region", "north")));
      Assertions.assertFalse(repeated.created());
      Assertions.assertEquals(TRIGGER_TIME.toString(), repeated.workflowAggregateId());
      Assertions.assertEquals(1, WorkflowStartConfiguration.AGGREGATES.size());
      Assertions
          .assertEquals(
              "changed meanwhile",
              WorkflowStartConfiguration.AGGREGATES.get(TRIGGER_TIME.toString()).getRegion());

      // (c) the signal start passes through the application's method, which sees the
      // trigger and the variables
      final var signalStart = dummyAdapter
          .startWorkflowByBpms(
              MODULE, PROCESS, context(BpmsStartTrigger.Kind.SIGNAL, SIGNAL_EVENT, Map.of("region", "south")));

      Assertions.assertTrue(signalStart.created());
      // a signal has no natural identity, so the ID is generated
      Assertions.assertNotEquals(TRIGGER_TIME.toString(), signalStart.workflowAggregateId());
      final var signalAggregate = WorkflowStartConfiguration.AGGREGATES
          .get(signalStart.workflowAggregateId());
      Assertions.assertEquals("SIGNAL/OrderReceived", signalAggregate.getStartedBy());
      Assertions.assertEquals("SOUTH", signalAggregate.getRegion());

    }

  }

  @Test
  public void theEndOfAWorkflowIsReportedToTheApplication() throws IOException {

    WorkflowStartConfiguration.AGGREGATES.clear();

    try (var testApp = buildTestApp(); var context = runTestApplication(
        testApp,
        WorkflowStartConfiguration.class,
        StartEventsConfiguration.class)) {

      final var dummyAdapter = context
          .getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);

      // the model pays for the notification only where the application asked for
      // one: the workflow service of 'TimerProcess' has a @WorkflowEnded method,
      // the other processes of the module have none
      Assertions
          .assertEquals(
              List.of(PROCESS),
              dummyAdapter.getProcessesWithEndListener(),
              "an end listener may only be attached where a @WorkflowEnded method exists");

      final var aggregate = new WorkflowStartAggregate();
      aggregate.setId("4711");
      WorkflowStartConfiguration.AGGREGATES.put("4711", aggregate);

      dummyAdapter
          .notifyWorkflowEnded(
              MODULE,
              PROCESS,
              new io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext() {

                @Override
                public String getWorkflowAggregateId() {
                  return "4711";
                }

                @Override
                public WorkflowEnd.Kind getKind() {
                  return WorkflowEnd.Kind.COMPLETED;
                }

                @Override
                public java.time.Instant getEndTime() {
                  return TRIGGER_TIME;
                }

                @Override
                public String getEndEventId() {
                  return "Event_Done";
                }

                @Override
                public String getAdapterId() {
                  return "test";
                }

              });

      // the method ran against the loaded aggregate and its change was saved
      Assertions
          .assertEquals("COMPLETED/Event_Done", WorkflowStartConfiguration.AGGREGATES.get("4711").getRegion());

      // the notification proves which BPMS held the workflow AND that it is over, so
      // the election hint is marked rather than refreshed: it still answers the
      // operation which crossed the end and leaves the cache long before a living one
      final var statistics = context
          .getBean(io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheStatistics.class);
      Assertions.assertEquals(1, statistics.getEndedMarks());
      Assertions
          .assertEquals(
              1,
              ((io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCache) context
                  .getBean(io.vanillabp.integration.spi.WorkflowAdapterCache.class))
                  .getStatistics()
                  .getEndedSize());

    }

  }

  @Test
  public void aMethodWithoutSuchAStartEventFailsTheBoot() throws IOException {

    WorkflowStartConfiguration.AGGREGATES.clear();

    try (var testApp = buildTestApp()) {

      final var failure = Assertions
          .assertThrows(
              Exception.class,
              () -> runTestApplication(
                  testApp,
                  WorkflowStartConfiguration.class,
                  NoStartEventConfiguration.class).close());

      // the guiding message names the method, the process and what to do about it
      final var message = rootCauseMessage(failure);
      Assertions.assertTrue(message.contains(WorkflowStartWorkflowService.class.getName()), message);
      Assertions.assertTrue(message.contains(PROCESS), message);
      Assertions.assertTrue(message.contains("startWorkflow"), message);

    }

  }

  private String rootCauseMessage(
      final Throwable failure) {

    var cause = failure;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return String.valueOf(cause.getMessage());

  }

}
