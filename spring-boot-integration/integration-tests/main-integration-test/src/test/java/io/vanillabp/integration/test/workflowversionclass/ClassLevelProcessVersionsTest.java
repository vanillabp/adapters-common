package io.vanillabp.integration.test.workflowversionclass;

import java.io.IOException;
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
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.deployment.DeploymentTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.spi.service.BpmnProcess;

/**
 * Acceptance test of <code>&#64;BpmnProcess(version = ...)</code>: two workflow service
 * classes serve one BPMN task of one process, each bound to a generation of the model by
 * the range of the {@link BpmnProcess} it declares, and not one method repeats that
 * range. The version the adapter reports decides which of the two classes runs.
 * <p>
 * The delivery a BPMS reports no version for is part of the acceptance: a method which
 * INHERITS a range is as restricted as one naming it, so no method serves such a
 * delivery, and the message says where the range came from - the method it complains
 * about carries no attribute at all.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ClassLevelProcessVersionsTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "ClassVersionedProcess";

  @Configuration
  static class ClassVersionsConfiguration {

    static final Map<String, ClassVersionedAggregate> AGGREGATES = new ConcurrentHashMap<>();

    /**
     * The versions the "BPMS" has.
     */
    static final List<DeployedProcessVersion> VERSIONS = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Bean
    AggregatePersistenceAware<ClassVersionedAggregate> classVersionedPersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<ClassVersionedAggregate> getAggregateClass() {
          return ClassVersionedAggregate.class;
        }

        @Override
        public ClassVersionedAggregate save(
            final ClassVersionedAggregate aggregate) {
          AGGREGATES.put(aggregate.getId(), aggregate);
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final ClassVersionedAggregate aggregate) {
          return aggregate.getId();
        }

        @Override
        public Class<?> getAggregateIdType() {
          return String.class;
        }

        @Override
        public ClassVersionedAggregate loadById(
            final Object aggregateId) {
          return AGGREGATES.get(aggregateId);
        }

      };

    }

    @Bean
    DataSource classVersionedDataSource() {

      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();

    }

    @Bean
    PlatformTransactionManager transactionManager(
        final DataSource classVersionedDataSource) {

      return new DataSourceTransactionManager(classVersionedDataSource);

    }

    /**
     * Stands in for the BPMN model: the one task both classes are wired to.
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

    @Bean
    DummyProcessVersionSource processVersionSource() {

      return (
          adapterId,
          workflowModuleId,
          bpmnProcessId) -> List.copyOf(VERSIONS);

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
                resources-location: classpath*:test-module/processes/workflowversionclass
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
            LoanApprovalUpToTwo.class,
            LoanApprovalAfterTwo.class,
            WorkflowModuleConfiguration.class,
            ClassVersionsConfiguration.class,
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

  private io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext context(
      final String aggregateId,
      final String processVersion) {

    return new io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext() {

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

  @Test
  @DisplayName("The deployed version picks the workflow service class, no method naming a version")
  public void theProcessVersionDecidesWhichClassServesTheTask() throws IOException {

    ClassVersionsConfiguration.AGGREGATES.clear();
    ClassVersionsConfiguration.VERSIONS.clear();
    ClassVersionsConfiguration.VERSIONS.add(DeployedProcessVersion.of("1", null));
    ClassVersionsConfiguration.VERSIONS.add(DeployedProcessVersion.of("2", null));
    ClassVersionsConfiguration.VERSIONS.add(DeployedProcessVersion.of("3", null));

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      final var dummyAdapter = context.getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);

      final var aggregate = new ClassVersionedAggregate();
      aggregate.setId("4711");
      ClassVersionsConfiguration.AGGREGATES.put("4711", aggregate);

      dummyAdapter.invokeTask(MODULE, PROCESS, context("4711", "2"));
      Assertions.assertEquals("upToTwo", ClassVersionsConfiguration.AGGREGATES.get("4711").getServedBy());

      dummyAdapter.invokeTask(MODULE, PROCESS, context("4711", "3"));
      Assertions.assertEquals("afterTwo", ClassVersionsConfiguration.AGGREGATES.get("4711").getServedBy());

      // an inherited range restricts as much as one written on the method, so a BPMS
      // reporting no version reaches neither class - and the message names the
      // declaration the range came from, since neither method carries one
      final var unreported = Assertions.assertThrows(
          IllegalStateException.class,
          () -> dummyAdapter.invokeTask(MODULE, PROCESS, context("4711", null)));
      Assertions.assertTrue(
          unreported.getMessage().contains("inherits its range from the @BpmnProcess"),
          unreported.getMessage());
      Assertions.assertEquals(
          "afterTwo",
          ClassVersionsConfiguration.AGGREGATES.get("4711").getServedBy(),
          "no method ran");

    }

  }

}
