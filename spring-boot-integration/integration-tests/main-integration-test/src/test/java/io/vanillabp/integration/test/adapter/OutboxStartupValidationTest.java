package io.vanillabp.integration.test.adapter;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.TestTransactionRunnerConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.sample.SampleWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;

/**
 * Startup validation of the phase-two outbox: everything an application sends to its
 * BPMS is dispatched after the caller's transaction committed, so every application
 * needs a resolvable outbox and needs it AT STARTUP - the boot fails with a guiding
 * message naming the remedies instead of surfacing the gap at the first workflow
 * start.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OutboxStartupValidationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

  @Test
  public void anApplicationWithoutOutboxFailsAtStartupWithRemedies() {

    this.contextRunner
        .withPropertyValues("spring.config.location=classpath:application.yaml")
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(
            WorkflowModuleConfiguration.class, TestPersistenceConfiguration.class, SampleWorkflowService.class)
        .withConfiguration(
            AutoConfigurations.of(
                DummyAdapterConfiguration.class, DummyAdapterProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class))
        .run(context -> {

          Assertions.assertNotNull(context.getStartupFailure(), "boot has to fail with a guiding message");

          var cause = context.getStartupFailure();
          while (cause.getCause() != null) {
            cause = cause.getCause();
          }
          final var message = String.valueOf(cause.getMessage());
          Assertions.assertTrue(
              message.contains("is dispatched through a PhaseTwoOutbox"),
              "expected the guiding message but got: "
                  + message);
          // the message names the aggregate of the workflow service this application
          // brought and ALL remedies
          Assertions.assertTrue(
              message.contains("for aggregate 'io.vanillabp.integration.test."),
              "the message has to name an aggregate of the test application: "
                  + message);
          Assertions.assertTrue(message.contains("spring-boot-starter-data-jpa"));
          Assertions.assertTrue(message.contains("spring-boot-starter-data-mongodb"));
          Assertions.assertTrue(message.contains("PhaseTwoOutbox"));
          Assertions.assertTrue(message.contains("PhaseTwoOutboxAware"));

        });

  }

  @Test
  public void anApplicationBringingAStoreBoots() {

    this.contextRunner
        .withPropertyValues("spring.config.location=classpath:application.yaml")
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(
            WorkflowModuleConfiguration.class, TestPersistenceConfiguration.class,
            TestPhaseTwoOutboxConfiguration.class, TestTransactionRunnerConfiguration.class,
            SampleWorkflowService.class)
        .withConfiguration(
            AutoConfigurations.of(
                DummyAdapterConfiguration.class, DummyAdapterProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class))
        .run(context -> {

          Assertions.assertNull(context.getStartupFailure(), "context should start");
          Assertions.assertEquals(1, context.getBeansOfType(PhaseTwoOutbox.class).size());

        });

  }

}
