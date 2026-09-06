package io.vanillabp.integration.test.delivery;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.migration.delivery.TaskDeliveryRetentionCleanup;
import io.vanillabp.integration.config.VanillaBpConfigurationProperties;
import io.vanillabp.integration.delivery.JdbcTaskDeliveryLogAutoConfiguration;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.sample.SampleWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;

/**
 * Which of the two retentions the JDBC delivery log runs with, asserted on a booted
 * application rather than on a properties object: the two numbers are set to different
 * values here, so a store reading the wrong one cannot pass by accident.
 * <p>
 * They were one property until the outbound deduplication window ended with the dispatch.
 * Since then the outbox number decides how long a dispatched entry stays readable during
 * support, and the delivery number decides whether a late redelivery runs the business
 * code a second time.
 */
@ExtendWith(SuppressOutputExtension.class)
public class DeliveryRetentionWiringTest {

  /**
   * A data source, so the JDBC delivery log is created at all, plus the transaction
   * manager it rides on. The dummy adapter stays EMBEDDED here (no two-phase commit), so
   * no outbox is required - what is asserted is which retention the delivery log runs
   * with, and that question is the same either way.
   */
  @Configuration
  static class DataSourceConfiguration {

    @Bean
    DataSource retentionDataSource() {

      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();

    }

    @Bean
    PlatformTransactionManager transactionManager(
        final DataSource retentionDataSource) {

      return new DataSourceTransactionManager(retentionDataSource);

    }

  }

  private ListAppender<ILoggingEvent> logWatcher;

  @BeforeEach
  public void watchTheCleanup() {

    logWatcher = new ListAppender<>();
    logWatcher.start();
    ((Logger) LoggerFactory.getLogger(TaskDeliveryRetentionCleanup.class)).addAppender(logWatcher);

  }

  @AfterEach
  public void stopWatchingTheCleanup() {

    ((Logger) LoggerFactory.getLogger(TaskDeliveryRetentionCleanup.class)).detachAndStopAllAppenders();

  }

  private List<String> cleanupLines() {

    return logWatcher.list
        .stream()
        .map(ILoggingEvent::getFormattedMessage)
        .toList();

  }

  private ApplicationContextRunner runner(
      final String... properties) {

    return new ApplicationContextRunner()
        .withPropertyValues(properties)
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(
            TestPersistenceConfiguration.class, TestPhaseTwoOutboxConfiguration.class,
            SampleWorkflowService.class,
            WorkflowModuleConfiguration.class,
            DataSourceConfiguration.class)
        .withConfiguration(
            AutoConfigurations.of(
                DummyAdapterConfiguration.class,
                DummyAdapterProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class,
                JdbcTaskDeliveryLogAutoConfiguration.class));

  }

  @Test
  @DisplayName("The delivery log runs with the delivery retention, the outbox keeps its own")
  public void theDeliveryLogReadsItsOwnRetention() {

    runner(
        "vanillabp.outbox.retention=PT1H",
        "vanillabp.delivery.retention=P30D")
        .run(context -> {
          Assertions.assertNull(context.getStartupFailure(), "the application boots");
          final var properties = context.getBean(VanillaBpConfigurationProperties.class);
          Assertions
              .assertEquals(
                  java.time.Duration.ofDays(30),
                  properties.resolvedDeliveryRetention());
          Assertions
              .assertEquals(
                  java.time.Duration.ofHours(1),
                  properties.getOutbox().getRetention(),
                  "the outbox number is not dragged along by the delivery one");
          final var line = cleanupLines()
              .stream()
              .filter(candidate -> candidate.contains("kept for"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("the cleanup did not say what it runs with: "
                  + cleanupLines()));
          Assertions.assertTrue(line.contains("PT720H") || line.contains("P30D"), line);
          Assertions
              .assertTrue(
                  line.contains("vanillabp.delivery.retention"),
                  "the line names the property somebody would change: "
                      + line);
        });

  }

  @Test
  @DisplayName("Without a delivery retention the log follows the outbox number")
  public void theDeliveryLogFallsBackToTheOutboxRetention() {

    // the upgrade case: one property governed both windows, so an installation which
    // lowered it keeps the behaviour it had
    runner("vanillabp.outbox.retention=PT1H")
        .run(context -> {
          Assertions.assertNull(context.getStartupFailure(), "the application boots");
          Assertions
              .assertEquals(
                  java.time.Duration.ofHours(1),
                  context.getBean(VanillaBpConfigurationProperties.class).resolvedDeliveryRetention());
          final var line = cleanupLines()
              .stream()
              .filter(candidate -> candidate.contains("kept for"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("the cleanup did not say what it runs with: "
                  + cleanupLines()));
          Assertions.assertTrue(line.contains("PT1H"), line);
        });

  }

}
