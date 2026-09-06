package io.vanillabp.integration.test.workflowtask;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.bpmsdouble.DummyHealthSource;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.migration.config.MetricsProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.observability.DeliveryMdc;
import io.vanillabp.integration.adapter.migration.observability.MicrometerVanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.spi.health.AdapterHealth;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.health.VanillaBpHealthIndicator;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.deployment.DeploymentTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;

/**
 * Acceptance test of what an operator gets to see on Spring Boot: every
 * task delivery is counted by outcome and measured, it carries a logging context
 * naming the workflow it belongs to, and the BPMS adapters contribute what they know
 * about their BPMS to the health endpoint.
 * <p>
 * The dummy adapter plays the BPMS, as in {@link WorkflowTaskProcessingTest} whose
 * fixture (aggregate, handlers, persistence) this test reuses - what is asserted here
 * is not the delivery but what the delivery leaves behind.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ObservabilityTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TaskProcess";

  private static final String ADAPTER = "test";

  /**
   * What the dummy adapter answers when it is asked for its health - set per test.
   */
  private static final AtomicReference<DummyHealthSource> HEALTH = new AtomicReference<>();

  @Configuration
  static class ObservabilityConfiguration {

    @Bean
    DummyHealthSource dummyHealthSource() {

      return adapterId -> {
        final var source = HEALTH.get();
        return source == null
            ? null
            : source.healthOf(adapterId);
      };

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
                resources-location: classpath*:test-module/processes/workflowtask
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
            TaskProcessingWorkflowService.class,
            WorkflowModuleConfiguration.class,
            WorkflowTaskProcessingTest.TaskProcessingConfiguration.class,
            ObservabilityConfiguration.class,
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

  private TaskInvocationContext context(
      final String taskDefinition,
      final String aggregateId) {

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
      public String getAdapterId() {
        return ADAPTER;
      }

      @Override
      public String getDeliveryId() {
        return "delivery-"
            + aggregateId;
      }

    };

  }

  private void storeAggregate(
      final String id) {

    final var aggregate = new TaskProcessingAggregate();
    aggregate.setId(id);
    aggregate.setStatus("new");
    WorkflowTaskProcessingTest.TaskProcessingConfiguration.AGGREGATES.put(id, aggregate);

  }

  @Test
  @DisplayName("Every delivery is counted by its outcome and measured")
  public void deliveriesAreCountedByOutcomeAndMeasured() throws IOException {

    HEALTH.set(null);
    WorkflowTaskProcessingTest.TaskProcessingConfiguration.AGGREGATES.clear();

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      final var registry = new SimpleMeterRegistry();
      context
          .getBean(MicrometerVanillaBpMetrics.class)
          .bindTo(registry);

      final var dummyAdapter = context.getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);

      storeAggregate("m-1");
      dummyAdapter.invokeTask(MODULE, PROCESS, context("processTask", "m-1"));
      storeAggregate("m-2");
      dummyAdapter.invokeTask(MODULE, PROCESS, context("raiseBpmnError", "m-2"));
      storeAggregate("m-3");
      dummyAdapter.invokeTask(MODULE, PROCESS, context("asyncTask", "m-3"));
      storeAggregate("m-4");
      Assertions
          .assertThrows(
              IllegalStateException.class,
              () -> dummyAdapter.invokeTask(MODULE, PROCESS, context("failTask", "m-4")));

      Assertions
          .assertEquals(
              1.0,
              deliveries(registry, "processTask", "completed"),
              "a handler which returned is one completed delivery");
      Assertions
          .assertEquals(
              1.0,
              deliveries(registry, "raiseBpmnError", "bpmn-error"),
              "a TaskException is a BPMN error, not a failure");
      Assertions
          .assertEquals(
              1.0,
              deliveries(registry, "asyncTask", "pending"),
              "a task waiting for its asynchronous completion is pending");
      Assertions
          .assertEquals(
              1.0,
              deliveries(registry, "failTask", "failed"),
              "a rolled-back delivery is counted as failed");

      final var timer = registry
          .get(VanillaBpMetrics.TASK_DELIVERY_DURATION)
          .tag(VanillaBpMetrics.TAG_ADAPTER, ADAPTER)
          .tag(VanillaBpMetrics.TAG_TASK_DEFINITION, "processTask")
          .timer();
      Assertions.assertEquals(1L, timer.count(), "the delivery is measured as well");
      Assertions
          .assertTrue(
              timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS) > 0,
              "and the measurement is not zero");

      Assertions
          .assertEquals(
              List.of(ADAPTER),
              registry
                  .get(VanillaBpMetrics.TASK_DELIVERIES)
                  .tag(VanillaBpMetrics.TAG_TASK_DEFINITION, "processTask")
                  .tag(VanillaBpMetrics.TAG_OUTCOME, "completed")
                  .counter()
                  .getId()
                  .getTags()
                  .stream()
                  .filter(tag -> tag
                      .getKey()
                      .equals(VanillaBpMetrics.TAG_ADAPTER))
                  .map(io.micrometer.core.instrument.Tag::getValue)
                  .toList(),
              "the adapter is a tag, never part of the meter's name");

    }

  }

  private double deliveries(
      final SimpleMeterRegistry registry,
      final String taskDefinition,
      final String outcome) {

    return registry
        .get(VanillaBpMetrics.TASK_DELIVERIES)
        .tag(VanillaBpMetrics.TAG_ADAPTER, ADAPTER)
        .tag(VanillaBpMetrics.TAG_WORKFLOW_MODULE, MODULE)
        .tag(VanillaBpMetrics.TAG_BPMN_PROCESS, PROCESS)
        .tag(VanillaBpMetrics.TAG_TASK_DEFINITION, taskDefinition)
        .tag(VanillaBpMetrics.TAG_OUTCOME, outcome)
        .counter()
        .count();

  }

  @Test
  @DisplayName("The logging context names the workflow while the handler runs, and is gone afterwards")
  public void mdcIsSetDuringDeliveryAndRestoredAfterwards() throws IOException {

    HEALTH.set(null);
    WorkflowTaskProcessingTest.TaskProcessingConfiguration.AGGREGATES.clear();

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      final var dummyAdapter = context.getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);

      org.slf4j.MDC.put("application.own.key", "untouched");
      try {

        storeAggregate("mdc-1");
        dummyAdapter.invokeTask(MODULE, PROCESS, context("recordMdc", "mdc-1"));

        final var during = TaskProcessingWorkflowService.MDC_DURING_TASK;
        Assertions.assertEquals(ADAPTER, during.get(DeliveryMdc.ADAPTER));
        Assertions.assertEquals(MODULE, during.get(DeliveryMdc.WORKFLOW_MODULE));
        Assertions.assertEquals(PROCESS, during.get(DeliveryMdc.BPMN_PROCESS));
        Assertions.assertEquals("mdc-1", during.get(DeliveryMdc.WORKFLOW_AGGREGATE_ID));
        Assertions.assertEquals("recordMdc", during.get(DeliveryMdc.TASK_DEFINITION));
        Assertions.assertEquals("delivery-mdc-1", during.get(DeliveryMdc.DELIVERY_ID));
        Assertions
            .assertEquals(
                "untouched",
                during.get("application.own.key"),
                "VanillaBP sets its own keys and touches nothing else");

        DeliveryMdc.KEYS
            .forEach(key -> Assertions
                .assertNull(
                    org.slf4j.MDC.get(key),
                    "the key '%s' has to be gone once the delivery is done".formatted(key)));

        // the same on the failure path: a handler which throws must not leave its
        // workflow in the context of whatever the thread does next
        storeAggregate("mdc-2");
        Assertions
            .assertThrows(
                IllegalStateException.class,
                () -> dummyAdapter.invokeTask(MODULE, PROCESS, context("recordMdcAndFail", "mdc-2")));
        Assertions
            .assertEquals(
                "mdc-2",
                TaskProcessingWorkflowService.MDC_DURING_TASK.get(DeliveryMdc.WORKFLOW_AGGREGATE_ID));
        DeliveryMdc.KEYS
            .forEach(key -> Assertions
                .assertNull(
                    org.slf4j.MDC.get(key),
                    "the key '%s' has to be gone after a failed delivery as well".formatted(key)));

        Assertions.assertEquals("untouched", org.slf4j.MDC.get("application.own.key"));

      } finally {
        org.slf4j.MDC.remove("application.own.key");
      }

    }

  }

  @Test
  @DisplayName("An adapter which is not configured yet is not an outage, and the detail names it")
  public void unconfiguredAdapterIsNotUnhealthy() throws IOException {

    HEALTH
        .set(adapterId -> AdapterHealth
            .unknown(
                adapterId,
                "dummy",
                "The adapter is not configured yet",
                AdapterHealth
                    .detailsBuilder()
                    .with("address", "<none>")
                    .build()));

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      final var health = context
          .getBean(VanillaBpHealthIndicator.class)
          .health();

      Assertions
          .assertEquals(
              Status.UNKNOWN,
              health.getStatus(),
              "an application which booted with a guiding warning is not an outage");
      Assertions
          .assertTrue(
              health
                  .getDetails()
                  .containsKey(ADAPTER),
              "the detail is named after the adapter id, so an operator knows which system is meant");

    }

  }

  @Test
  @DisplayName("An unreachable BPMS is down, naming the adapter and the address")
  public void unreachableBpmsIsDown() throws IOException {

    HEALTH
        .set(adapterId -> AdapterHealth
            .down(
                adapterId,
                "dummy",
                "Connection refused",
                AdapterHealth
                    .detailsBuilder()
                    .with("address", "http://localhost:26500")
                    .build()));

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      final var health = context
          .getBean(VanillaBpHealthIndicator.class)
          .health();

      Assertions.assertEquals(Status.DOWN, health.getStatus());
      @SuppressWarnings("unchecked")
      final var detail = (java.util.Map<String, Object>) health
          .getDetails()
          .get(ADAPTER);
      Assertions.assertEquals("DOWN", detail.get("status"));
      Assertions.assertEquals("Connection refused", detail.get("description"));
      Assertions
          .assertEquals(
              "http://localhost:26500",
              detail.get("address"),
              "the address is what lets an operator act without reading the configuration");

    }

  }

  @Test
  @DisplayName("An adapter contributing nothing is absent, one which throws is down")
  public void adapterWithoutCheckIsAbsentAndAThrowingOneIsDown() throws IOException {

    HEALTH.set(null);

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      final var indicator = context.getBean(VanillaBpHealthIndicator.class);
      Assertions
          .assertTrue(
              indicator
                  .health()
                  .getDetails()
                  .isEmpty(),
              "an adapter which checks nothing is absent rather than reported as healthy");

      HEALTH
          .set(adapterId -> {
            throw new IllegalStateException("the check itself is broken");
          });
      final var broken = indicator.health();
      Assertions.assertEquals(Status.DOWN, broken.getStatus());
      @SuppressWarnings("unchecked")
      final var detail = (java.util.Map<String, Object>) broken
          .getDetails()
          .get(ADAPTER);
      Assertions
          .assertTrue(
              detail
                  .get("description")
                  .toString()
                  .contains("the check itself is broken"),
              "an adapter which cannot answer its own question is a defect worth seeing, but got: "
                  + detail);

    }

  }

  private ApplicationContextRunner contextRunner() {

    return new ApplicationContextRunner()
        .withPropertyValues("spring.config.location=classpath:application.yaml")
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(WorkflowModuleConfiguration.class, TestPersistenceConfiguration.class,
            TestPhaseTwoOutboxConfiguration.class)
        .withConfiguration(
            AutoConfigurations
                .of(
                    DummyAdapterConfiguration.class, DummyAdapterProcessServiceConfiguration.class,
                    WorkflowModuleAutoConfiguration.class,
                    SpringBootMigrationAdapterAutoConfiguration.class));

  }

  @Test
  @DisplayName("How long a gauge's measurement is held is configured and validated at startup")
  public void theGaugeCacheIsBoundAndValidated() {

    contextRunner()
        .run(context -> Assertions
            .assertEquals(
                MetricsProperties.DEFAULT_GAUGE_CACHE,
                context
                    .getBean(MigrationAdapterProperties.class)
                    .getMetrics()
                    .resolvedGaugeCache(),
                "an unconfigured application holds a measurement for one collection interval"));

    contextRunner()
        .withPropertyValues("vanillabp.metrics.gauge-cache=PT0S")
        .run(context -> Assertions
            .assertEquals(
                java.time.Duration.ZERO,
                context
                    .getBean(MigrationAdapterProperties.class)
                    .getMetrics()
                    .resolvedGaugeCache(),
                "zero is how a test asks for the real value on every collection"));

    contextRunner()
        .withPropertyValues("vanillabp.metrics.gauge-cache=-PT1S")
        .run(context -> {
          final var failure = context.getStartupFailure();
          Assertions.assertNotNull(failure, "a negative span has to end the boot");
          Assertions
              .assertTrue(
                  messagesOf(failure).contains(MetricsProperties.GAUGE_CACHE_PROPERTY),
                  "and the message has to name the property but got: "
                      + messagesOf(failure));
        });

  }

  /**
   * The messages along the chain of causes - a startup failure wraps the guiding one.
   */
  private static String messagesOf(
      final Throwable failure) {

    final var messages = new StringBuilder();
    for (var cause = failure; cause != null; cause = cause.getCause() == cause
        ? null
        : cause.getCause()) {
      messages
          .append(cause.getMessage())
          .append('\n');
    }
    return messages.toString();

  }

  @Test
  @DisplayName("Without Micrometer the application boots and reports no metrics")
  public void withoutMicrometerNoMeters() {

    contextRunner()
        .withClassLoader(new FilteredClassLoader(MeterRegistry.class))
        .run(context -> {

          Assertions.assertNull(context.getStartupFailure(), "the application has to boot");
          Assertions
              .assertEquals(
                  0,
                  context
                      .getBeanNamesForType(MicrometerVanillaBpMetrics.class).length,
                  "nothing of Micrometer is loaded where the application does not bring it");

        });

  }

}
