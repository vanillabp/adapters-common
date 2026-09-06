package io.vanillabp.integration.test.adapter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.core.ResolvableType;

import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.TestTransactionRunnerConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.sample.Aggregate;
import io.vanillabp.integration.test.sample.SampleWorkflowService;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.spi.process.ProcessService;

/**
 * Convention over configuration on Spring Boot: an application
 * configures only what DEVIATES from the convention.
 * <ul>
 * <li>one adapter dependency + one workflow module &rarr; NO
 * <code>vanillabp.*</code> property at all,</li>
 * <li>several BPMS &rarr; nothing but <code>vanillabp.prioritized-adapters</code>,</li>
 * <li>a custom adapter id (an id which is not an adapter type) still needs its
 * <code>type</code> - it can never be derived.</li>
 * </ul>
 */
@ExtendWith(SuppressOutputExtension.class)
public class ConfigurationConventionsTest {

  /**
   * The SECOND adapter type of the "several BPMS" case: the type announcement and
   * the log-only services of {@code TwoAdapterTypesDeploymentTest} serving the
   * DERIVED adapter id (which is the adapter type itself).
   */
  @org.springframework.context.annotation.Configuration
  static class SecondAdapterConfiguration {

    @org.springframework.context.annotation.Bean
    io.vanillabp.integration.adapter.AdapterConfigurationBase secondAdapterType() {

      return new io.vanillabp.integration.test.deployment.TwoAdapterTypesDeploymentTest.SecondAdapterConfiguration();

    }

    @org.springframework.context.annotation.Bean
    io.vanillabp.integration.adapter.spi.AdapterDeploymentService<Object, Object> secondDeploymentService() {

      return new io.vanillabp.integration.test.deployment.TwoAdapterTypesDeploymentTest.SecondDeploymentService() {
        @Override
        public String getAdapterId() {
          return "dummy2";
        }
      };

    }

    @org.springframework.context.annotation.Bean
    io.vanillabp.integration.adapter.spi.MigratableProcessService<Object> secondProcessService() {

      return new io.vanillabp.integration.test.deployment.TwoAdapterTypesDeploymentTest.SecondProcessService() {
        @Override
        public String getAdapterId() {
          return "dummy2";
        }
      };

    }

  }

  /**
   * Persistence double for the sample aggregate (this test has no database).
   */
  @org.springframework.context.annotation.Configuration
  static class AggregatePersistenceConfiguration {

    @org.springframework.context.annotation.Bean
    io.vanillabp.integration.spi.AggregatePersistenceAware<Aggregate> testAggregatePersistence() {

      return new io.vanillabp.integration.spi.AggregatePersistenceAware<>() {

        @Override
        public Class<Aggregate> getAggregateClass() {
          return Aggregate.class;
        }

        @Override
        public Aggregate save(
            final Aggregate aggregate) {
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final Aggregate aggregate) {
          return "4711";
        }

      };

    }

  }

  private static final Class<?>[] APPLICATION_CONFIGURATION = {
      DummyAdapterConfiguration.class, DummyAdapterProcessServiceConfiguration.class, WorkflowModuleAutoConfiguration.class, SpringBootMigrationAdapterAutoConfiguration.class, io.vanillabp.integration.deployment.DeploymentAutoConfiguration.class, TestPersistenceConfiguration.class, TestPhaseTwoOutboxConfiguration.class, TestTransactionRunnerConfiguration.class, SampleWorkflowService.class, WorkflowModuleConfiguration.class, AggregatePersistenceConfiguration.class
  };

  /**
   * An application whose classpath provides ONE adapter and ONE workflow module -
   * and not a single VanillaBP property.
   */
  private SpringBootTestApplication zeroConfigurationApplication() throws IOException {

    return SpringBootTestApplication.builder()
        .addResource("META-INF/workflow-module")
        .addResource("test-module/processes/dummy/DummyProcess.bpmn")
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build();

  }

  @SuppressWarnings("unchecked")
  private static ProcessService<Aggregate> processServiceOf(
      final org.springframework.context.ApplicationContext context) {

    return (ProcessService<Aggregate>) context
        .getBeanProvider(ResolvableType
            .forClassWithGenerics(ProcessService.class, Aggregate.class))
        .getObject();

  }

  @Test
  @DisplayName("One adapter, one workflow module, ZERO properties: boots, deploys and starts workflows")
  public void zeroConfigurationApplicationBootsAndRuns(
      final CapturedOutput output) throws IOException {

    try (var testApp = zeroConfigurationApplication(); var context = testApp
        .applicationBuilder(APPLICATION_CONFIGURATION)
        .run()) {

      final var properties = context.getBean(MigrationAdapterProperties.class);

      // the adapter section is derived from the single adapter type in classpath
      Assertions.assertEquals(Map.of("dummy", "dummy"), properties.adapterTypes());
      Assertions.assertEquals(List.of("dummy"), properties.getPrioritizedAdapters());
      // the workflow module found in classpath needs no section either
      Assertions.assertTrue(properties.getWorkflowModules().containsKey("test-module"));
      // and its BPMN is read from the conventional location
      Assertions.assertEquals(
          "classpath*:test-module/processes/dummy",
          properties
              .getAdapterResourcesLocationsFor("test-module", "dummy")
              .getFirst()
              .location());

      // the BPMN of that location really was deployed by the derived adapter
      Assertions.assertTrue(
          output
              .getAll()
              .contains("Dummy-Adapter[dummy]: Deploying"),
          () -> "expected the derived adapter to deploy the module's BPMN but got: "
              + output.getAll());

      // ... and the application can start a workflow
      processServiceOf(context).startWorkflow(new Aggregate());
      Assertions.assertTrue(
          output
              .getAll()
              .contains("Dummy-Adapter[dummy]: Starting workflow (phase one)"),
          () -> "expected the workflow to start in the derived adapter but got: "
              + output.getAll());

    }

  }

  @Test
  @DisplayName("Several BPMS: naming the order in 'prioritized-adapters' is the whole configuration")
  public void severalAdaptersNeedNothingButTheirOrder() throws IOException {

    final var applicationYaml = """
        vanillabp:
          prioritized-adapters:
            - dummy
            - dummy2
        """;

    final var configuration = new java.util.LinkedList<Class<?>>(List.of(APPLICATION_CONFIGURATION));
    configuration.add(SecondAdapterConfiguration.class);

    try (var testApp = SpringBootTestApplication.builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", applicationYaml)
        .addResource("test-module/processes/dummy/DummyProcess.bpmn")
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build(); var context = testApp
            .applicationBuilder(configuration.toArray(Class<?>[]::new))
            .run()) {

      final var properties = context.getBean(MigrationAdapterProperties.class);

      // both adapter sections are derived from the ids - which ARE adapter types
      Assertions.assertEquals(
          Map.of("dummy", "dummy", "dummy2", "dummy2"),
          properties.adapterTypes());
      Assertions.assertEquals(List.of("dummy", "dummy2"), properties.getPrioritizedAdapters());
      // each adapter reads its own BPMN dialect from its own location
      Assertions.assertEquals(
          "classpath*:test-module/processes/dummy2",
          properties
              .getAdapterResourcesLocationsFor("test-module", "dummy2")
              .getFirst()
              .location());

    }

  }

  @Test
  @DisplayName("A custom adapter id without 'type' fails the boot with a guiding message")
  public void customAdapterIdWithoutTypeIsReportedGuiding() throws IOException {

    final var applicationYaml = """
        vanillabp:
          prioritized-adapters:
            - my-bpms
        """;

    try (var testApp = SpringBootTestApplication.builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", applicationYaml)
        .addResource("test-module/processes/dummy/DummyProcess.bpmn")
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build()) {

      final var exception = Assertions.assertThrows(
          Exception.class,
          () -> testApp
              .applicationBuilder(APPLICATION_CONFIGURATION)
              .run()
              .close());

      final var message = rootCauseMessage(exception);
      Assertions.assertTrue(message.contains("my-bpms"), () -> message);
      Assertions.assertTrue(message.contains("vanillabp.adapters"), () -> message);

    }

  }

  private static String rootCauseMessage(
      final Throwable throwable) {

    var current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return String.valueOf(current.getMessage());

  }

}
