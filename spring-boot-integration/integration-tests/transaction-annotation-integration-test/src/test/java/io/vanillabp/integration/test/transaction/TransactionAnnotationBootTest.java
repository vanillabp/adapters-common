package io.vanillabp.integration.test.transaction;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;

import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.deployment.DeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.deployment.SpringBootDeploymentService;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.integration.workflowmodule.WorkflowModules;
import io.vanillabp.spi.process.ProcessService;

/**
 * Acceptance test of the startup check: an application whose
 * <code>&#64;WorkflowTask</code> handler carries a transaction annotation must not boot,
 * and the message has to name the class, the method and both remedies without sending
 * the developer to the documentation.
 * <p>
 * This is a module of its own because the platform scans the whole classpath for
 * {@code @WorkflowService} classes, so {@link TransactionalWorkflowService} fails every
 * boot next to it. The matrix of annotations, propagations and rollback rules is covered
 * by the core's unit tests; the accepted counterpart boots in
 * {@code main-integration-test}.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TransactionAnnotationBootTest {

  private static final String APPLICATION_YAML = """
      vanillabp:
        adapters:
          test:
            type: dummy
        workflow-modules:
          test-module:
            adapters:
              test:
                resources-location: classpath*:test-module/processes/transactional
      """;

  @Configuration
  @EnableConfigurationProperties
  static class TestConfiguration {

    @Bean
    SpringBootDeploymentService springBootDeploymentService(
        final WorkflowModules allWorkflowModules,
        final MigrationAdapterProperties properties,
        final List<AdapterDeploymentService<?, ?>> deploymentServices,
        final List<ExtensionWiringService<?, ?>> wiringServices,
        final ObjectProvider<ProcessService<?>> processServices) {

      return new SpringBootDeploymentService(
          new DeploymentService(properties, deploymentServices, wiringServices), allWorkflowModules, processServices);

    }

    @Bean
    AggregatePersistenceAware<TransactionalAggregate> transactionalPersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<TransactionalAggregate> getAggregateClass() {
          return TransactionalAggregate.class;
        }

        @Override
        public TransactionalAggregate save(
            final TransactionalAggregate aggregate) {
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final TransactionalAggregate aggregate) {
          return aggregate.getId();
        }

        @Override
        public Class<?> getAggregateIdType() {
          return String.class;
        }

        @Override
        public TransactionalAggregate loadById(
            final Object aggregateId) {
          return null;
        }

      };

    }

    @Bean
    DataSource transactionalDataSource() {

      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();

    }

    @Bean
    PlatformTransactionManager transactionManager(
        final DataSource transactionalDataSource) {

      return new DataSourceTransactionManager(transactionalDataSource);

    }

  }

  @Test
  public void aTransactionAnnotationOnAWorkflowTaskMethodFailsTheBoot() throws IOException {

    try (var testApp = SpringBootTestApplication.builder()
        .addResource("META-INF/workflow-module", "test-module")
        .addResource("application.yaml", APPLICATION_YAML)
        .addResource("test-module/processes/transactional/TransactionalProcess.bpmn", "<definitions/>")
        .build()) {

      final var failure = Assertions.assertThrows(
          Exception.class,
          () -> testApp
              .applicationBuilder(
                  DummyAdapterConfiguration.class,
                  DummyAdapterProcessServiceConfiguration.class,
                  WorkflowModuleAutoConfiguration.class,
                  SpringBootMigrationAdapterAutoConfiguration.class,
                  TransactionalWorkflowService.class,
                  TestConfiguration.class)
              .run()
              .close());

      final var stackTrace = new StringWriter();
      failure.printStackTrace(new PrintWriter(stackTrace));
      final var reported = stackTrace.toString();
      Assertions.assertTrue(
          reported.contains("covered by a transaction annotation of the application"),
          "unexpected failure: "
              + reported);
      Assertions.assertTrue(
          reported.contains(TransactionalWorkflowService.class.getName()),
          "the offending class is not named: "
              + reported);
      Assertions.assertTrue(reported.contains("#assessRisk"), "the offending method is not named: "
          + reported);
      Assertions.assertTrue(
          reported.contains("org.springframework.transaction.annotation.Transactional"),
          "the offending annotation is not named: "
              + reported);
      // the remedy of the OFFENDING annotation: Spring's, not the one of every
      // annotation Spring Boot happens to honor
      Assertions.assertTrue(
          reported.contains("noRollbackFor = TaskException.class"),
          "the remedy is missing: "
              + reported);
      Assertions.assertTrue(
          reported.contains("remove the annotation from the workflow task method"),
          "the remedy is missing: "
              + reported);

    }

  }

}
