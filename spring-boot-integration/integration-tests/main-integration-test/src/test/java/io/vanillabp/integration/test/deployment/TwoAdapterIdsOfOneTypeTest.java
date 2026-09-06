package io.vanillabp.integration.test.deployment;

import java.io.IOException;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;

import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.processservice.ProcessServiceSpringBean;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
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
 * Acceptance test of the per-adapter-id bean convention (the adapter config model) - the
 * structural foundation of the migration scenario: TWO adapter ids of ONE
 * type boot together, the adapter registers one {@code MigratableProcessService} and
 * one {@code AdapterDeploymentService} element bean PER configured id, BOTH
 * deployment services receive {@code deployResources}, and a workflow module
 * prioritizing one id starts its workflows there only.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TwoAdapterIdsOfOneTypeTest {

  /**
   * Persistence double for the sample aggregate (the stubbed SpringDataUtil of
   * {@link TestPersistenceConfiguration} fails loudly on any use).
   */
  @Configuration
  static class AggregatePersistenceConfiguration {

    @Bean
    AggregatePersistenceAware<Aggregate> testAggregatePersistence() {

      return new AggregatePersistenceAware<>() {

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

  private static final String APPLICATION_YAML = """
      vanillabp:
        prioritized-adapters:
          - test
          - test2
        adapters:
          test:
            type: dummy
            test: 1
          test2:
            type: dummy
        workflow-modules:
          test-module:
            prioritized-adapters:
              - test2
              - test
            adapters:
              test:
                resources-location: classpath*:test-module/processes/dummy
              test2:
                resources-location: classpath*:test-module/processes/dummy
      test-module:
        nothing: there
      """;

  @Test
  public void twoIdsOfOneTypeBootWithPerIdBeans(
      final CapturedOutput output) throws IOException {

    try (var testApp = SpringBootTestApplication.builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", APPLICATION_YAML)
        .addResource("test-module/processes/dummy/DummyProcess.bpmn")
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build(); var context = testApp.applicationBuilder(
            DummyAdapterConfiguration.class,
            DummyAdapterProcessServiceConfiguration.class,
            WorkflowModuleAutoConfiguration.class,
            SpringBootMigrationAdapterAutoConfiguration.class,
            TestPersistenceConfiguration.class, TestPhaseTwoOutboxConfiguration.class,
            TestTransactionRunnerConfiguration.class,
            SampleWorkflowService.class,
            WorkflowModuleConfiguration.class,
            AggregatePersistenceConfiguration.class,
            DeploymentTest.TestConfig.class)
            .run()) {

      // one MigratableProcessService element bean per configured adapter id
      final var processServiceIds = context
          .getBeanProvider(MigratableProcessService.class)
          .stream()
          .map(processService -> ((MigratableProcessService<?>) processService).getAdapterId())
          .collect(Collectors.toSet());
      Assertions.assertEquals(java.util.Set.of("test", "test2"), processServiceIds);

      // one AdapterDeploymentService element bean per configured adapter id
      final var deploymentServiceIds = context
          .getBeanProvider(AdapterDeploymentService.class)
          .stream()
          .map(deploymentService -> ((AdapterDeploymentService<?, ?>) deploymentService).getAdapterId())
          .collect(Collectors.toSet());
      Assertions.assertEquals(java.util.Set.of("test", "test2"), deploymentServiceIds);

      // BOTH deployment services received deployResources
      final var capturedOutput = output.getAll();
      Assertions.assertTrue(
          capturedOutput.contains("Dummy-Adapter[test]: Deploying resources for test-module"),
          "expected deployment of id 'test' but got: "
              + capturedOutput);
      Assertions.assertTrue(
          capturedOutput.contains("Dummy-Adapter[test2]: Deploying resources for test-module"),
          "expected deployment of id 'test2' but got: "
              + capturedOutput);

      // the module prioritizes id 'test2' - a started workflow runs phase one
      // there and NEVER touches id 'test'
      @SuppressWarnings("unchecked")
      final ProcessService<Aggregate> processService = (ProcessService<Aggregate>) context
          .getBeanProvider(ResolvableType
              .forClassWithGenerics(ProcessService.class, Aggregate.class))
          .getObject();
      Assertions.assertInstanceOf(ProcessServiceSpringBean.class, processService);

      processService.startWorkflow(new Aggregate());

      final var afterStart = output.getAll();
      Assertions.assertTrue(
          afterStart.contains("Dummy-Adapter[test2]: Starting workflow (phase one)"),
          "expected phase one on the module's first-priority id 'test2' but got: "
              + afterStart);
      Assertions.assertFalse(
          afterStart.contains("Dummy-Adapter[test]: Starting workflow (phase one)"),
          "id 'test' must never be touched for this module but got: "
              + afterStart);

    }

  }

}
