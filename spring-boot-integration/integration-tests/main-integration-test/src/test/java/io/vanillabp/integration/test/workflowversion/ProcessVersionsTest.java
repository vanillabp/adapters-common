package io.vanillabp.integration.test.workflowversion;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
import io.vanillabp.bpmsdouble.DummyProcessVersionSource;
import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
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
 * Acceptance test of <code>&#64;WorkflowTask(version = ...)</code> with the
 * dummy adapter standing in for a BPMS: the version the adapter reports decides which
 * method serves a delivered task, ranges made of numbers are compared without asking
 * the BPMS, and a range naming a version TAG is resolved through the catalog the
 * adapter registered - including the query for a version this application never
 * deployed itself (another cluster node did, during a rolling deployment).
 */
@ExtendWith(SuppressOutputExtension.class)
public class ProcessVersionsTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "VersionedProcess";

  @Configuration
  static class VersionsConfiguration {

    static final Map<String, VersionedAggregate> AGGREGATES = new ConcurrentHashMap<>();

    /**
     * The versions the "BPMS" has - a test may add one while the application runs.
     */
    static final List<DeployedProcessVersion> VERSIONS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * How often the "BPMS" was asked - the query must not run per task delivery.
     */
    static final AtomicInteger QUERIES = new AtomicInteger();

    @Bean
    AggregatePersistenceAware<VersionedAggregate> versionedPersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<VersionedAggregate> getAggregateClass() {
          return VersionedAggregate.class;
        }

        @Override
        public VersionedAggregate save(
            final VersionedAggregate aggregate) {
          AGGREGATES.put(aggregate.getId(), aggregate);
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final VersionedAggregate aggregate) {
          return aggregate.getId();
        }

        @Override
        public Class<?> getAggregateIdType() {
          return String.class;
        }

        @Override
        public VersionedAggregate loadById(
            final Object aggregateId) {
          return AGGREGATES.get(aggregateId);
        }

      };

    }

    @Bean
    DataSource versionedDataSource() {

      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();

    }

    @Bean
    PlatformTransactionManager transactionManager(
        final DataSource versionedDataSource) {

      return new DataSourceTransactionManager(versionedDataSource);

    }

    /**
     * Stands in for the BPMN model: the one task all three methods are wired to.
     */
    @Bean
    DummyTaskWiringSource taskWiringSource() {

      return (
          adapterId,
          workflowModuleId,
          bpmnProcessId) -> PROCESS.equals(bpmnProcessId)
              ? List.of(new BpmnTaskSpec("Activity_Versioned", "versionedTask"))
              : List.of();

    }

    /**
     * Stands in for the BPMS query a real adapter runs to learn its version tags.
     */
    @Bean
    DummyProcessVersionSource processVersionSource() {

      return (
          adapterId,
          workflowModuleId,
          bpmnProcessId) -> {
        QUERIES.incrementAndGet();
        return List.copyOf(VERSIONS);
      };

    }

  }

  private static final String APPLICATION_YAML = """
      vanillabp:
        adapters:
          test:
            type: dummy
        workflow-modules:
          test-module:
            adapters:
              test:
                resources-location: classpath*:test-module/processes/workflowversion
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
            VersionedWorkflowService.class,
            WorkflowModuleConfiguration.class,
            VersionsConfiguration.class,
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
      final String aggregateId,
      final String processVersion) {

    return new TaskInvocationContext() {

      @Override
      public String getTaskDefinition() {
        return "versionedTask";
      }

      @Override
      public String getWorkflowAggregateId() {
        return aggregateId;
      }

      @Override
      public String getProcessVersion() {
        return processVersion;
      }

    };

  }

  private void storeAggregate(
      final String id) {

    final var aggregate = new VersionedAggregate();
    aggregate.setId(id);
    VersionsConfiguration.AGGREGATES.put(id, aggregate);

  }

  @Test
  public void theProcessVersionDecidesWhichMethodServesTheTask() throws IOException {

    VersionsConfiguration.AGGREGATES.clear();
    VersionsConfiguration.VERSIONS.clear();
    VersionsConfiguration.QUERIES.set(0);
    VersionsConfiguration.VERSIONS.add(DeployedProcessVersion.of("1", null));
    VersionsConfiguration.VERSIONS.add(DeployedProcessVersion.of("2", null));
    VersionsConfiguration.VERSIONS.add(DeployedProcessVersion.of("3", null));
    VersionsConfiguration.VERSIONS.add(DeployedProcessVersion.of("4", "release-2026"));

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      final var dummyAdapter = context.getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);

      // the version tag was resolved while the application booted, once
      Assertions.assertEquals(1, VersionsConfiguration.QUERIES.get());

      storeAggregate("4711");
      dummyAdapter.invokeTask(MODULE, PROCESS, context("4711", "2"));
      Assertions.assertEquals("upToTwo", VersionsConfiguration.AGGREGATES.get("4711").getServedBy());

      dummyAdapter.invokeTask(MODULE, PROCESS, context("4711", "3"));
      Assertions.assertEquals("three", VersionsConfiguration.AGGREGATES.get("4711").getServedBy());

      // the version carrying the tag - and no further BPMS query for it
      dummyAdapter.invokeTask(MODULE, PROCESS, context("4711", "4"));
      Assertions.assertEquals("tagged", VersionsConfiguration.AGGREGATES.get("4711").getServedBy());
      Assertions.assertEquals(1, VersionsConfiguration.QUERIES.get());

      // a BPMS reporting NO version reaches none of these methods: every one of them
      // names versions, and which of them applies is not something to guess
      final var unreported = Assertions.assertThrows(
          IllegalStateException.class,
          () -> dummyAdapter.invokeTask(MODULE, PROCESS, context("4711", null)));
      Assertions.assertTrue(unreported.getMessage().contains("reports no process version"),
          unreported.getMessage());
      Assertions.assertEquals(
          "tagged",
          VersionsConfiguration.AGGREGATES.get("4711").getServedBy(),
          "no method ran");

      // ANOTHER cluster node deploys version 5 and moves the tag to it: this node
      // has never seen that version, so it asks the BPMS while the task is dispatched
      VersionsConfiguration.VERSIONS.set(3, DeployedProcessVersion.of("4", null));
      VersionsConfiguration.VERSIONS.add(DeployedProcessVersion.of("5", "release-2026"));
      dummyAdapter.invokeTask(MODULE, PROCESS, context("4711", "5"));
      Assertions.assertEquals("tagged", VersionsConfiguration.AGGREGATES.get("4711").getServedBy());
      Assertions.assertTrue(
          VersionsConfiguration.QUERIES.get() > 1,
          "the version deployed by another node was looked up on demand");

      // a version no method serves is a defect naming the version
      final var unmatched = Assertions.assertThrows(
          IllegalStateException.class,
          () -> dummyAdapter.invokeTask(MODULE, PROCESS, context("4711", "4")));
      Assertions.assertTrue(unmatched.getMessage().contains("process version '4'"), unmatched.getMessage());

    }

  }

}
