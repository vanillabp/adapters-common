package io.vanillabp.integration.test.deployment;

import java.io.IOException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.TestTransactionRunnerConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.sample.Aggregate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * A <code>&#64;WorkflowTask</code> method matching no task of any BPMN process of its
 * workflow module is a defect the developer has to learn about while the application
 * starts - a typo in a task definition, or a method left behind after a model change,
 * otherwise stays silent until a workflow reaches the task.
 * <p>
 * The check itself is old; who runs it is new. It used to be the adapter's duty, written
 * down in the javadoc of the SPI and nowhere else, and Camunda 7 forgot it for a year.
 * Since the SPI was split into
 * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring} and
 * {@code WorkflowTaskInvoker}, the core runs it itself once the last adapter of a module
 * finished deploying - which is what this test holds: the dummy adapter does not call it
 * any more, and the boot still fails.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OrphanWorkflowTaskMethodTest {

  /**
   * Serves the one task of <code>DummyProcess.bpmn</code> and declares a second method
   * for a task nobody modelled.
   */
  @Service
  @WorkflowService(
      workflowAggregateClass = Aggregate.class,
      bpmnProcess = @io.vanillabp.spi.service.BpmnProcess(bpmnProcessId = "DummyProcess"))
  public static class OrphanMethodWorkflowService {

    @WorkflowTask
    public void juhu() {

      LoggerFactory.getLogger(OrphanMethodWorkflowService.class).info("the modelled task");

    }

    @WorkflowTask(taskDefinition = "activityNobodyModelled")
    public void typo() {

      LoggerFactory.getLogger(OrphanMethodWorkflowService.class).info("never runs");

    }

  }

  /**
   * What the BPMN of this test really contains: one service task, served by
   * {@code juhu}. Without such a source the dummy adapter reports no tasks at all, and a
   * module nothing was wired in is deliberately not reported - a model may arrive later
   * during a migration.
   */
  @org.springframework.context.annotation.Configuration
  static class WiringConfiguration {

    @org.springframework.context.annotation.Bean
    io.vanillabp.bpmsdouble.DummyTaskWiringSource orphanTaskWiringSource() {

      return (
          adapterId,
          workflowModuleId,
          bpmnProcessId) -> java.util.List
              .of(new io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec("Activity_1c9pa8d", "juhu"));

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
                resources-location: classpath*:test-module/processes/dummy
      """;

  @Test
  @DisplayName("A method matching no task ends the boot, naming the method and the fix")
  public void anOrphanMethodEndsTheBoot() throws IOException {

    try (var testApp = SpringBootTestApplication
        .builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", APPLICATION_YAML)
        .addResource("test-module/processes/dummy/DummyProcess.bpmn")
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build()) {

      // the check runs at the end of the deployment, which Spring does while it refreshes
      // the context - so the guiding message arrives wrapped
      final var failure = Assertions.assertThrows(
          Exception.class,
          () -> testApp
              .applicationBuilder(
                  DummyAdapterConfiguration.class,
                  DummyAdapterProcessServiceConfiguration.class,
                  WorkflowModuleAutoConfiguration.class,
                  SpringBootMigrationAdapterAutoConfiguration.class,
                  TestPersistenceConfiguration.class, TestPhaseTwoOutboxConfiguration.class,
                  TestTransactionRunnerConfiguration.class,
                  OrphanMethodWorkflowService.class,
                  WiringConfiguration.class,
                  WorkflowModuleConfiguration.class,
                  DeploymentTest.TestConfig.class)
              .run()
              .close());

      var cause = (Throwable) failure;
      while ((cause.getCause() != null) && !(cause instanceof IllegalStateException)) {
        cause = cause.getCause();
      }
      final var message = String.valueOf(cause.getMessage());
      Assertions.assertTrue(message.contains("typo"), message);
      Assertions.assertTrue(message.contains("activityNobodyModelled"), message);
      Assertions.assertTrue(message.contains("fix the annotation"), message);

    }

  }

}
