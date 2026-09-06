package io.vanillabp.integration.test.deployment;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.extension.dummy.springboot.wiring.DummyExtensionWiringConfiguration;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.deployment.DeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.deployment.SpringBootDeploymentService;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.TestTransactionRunnerConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.sample.SampleWorkflowService;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.integration.workflowmodule.WorkflowModules;
import io.vanillabp.spi.process.ProcessService;

@ExtendWith(SuppressOutputExtension.class)
public class DeploymentTest {

  @Configuration
  public static class TestConfig {

    @Bean
    public SpringBootDeploymentService springBootDeploymentService(
        final WorkflowModules allWorkflowModules,
        final MigrationAdapterProperties properties,
        final List<AdapterDeploymentService<?, ?>> deploymentServices,
        final List<ExtensionWiringService<?, ?>> wiringServices,
        final ObjectProvider<ProcessService<?>> processServices,
        final io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry workflowTaskWiring) {

      // the wiring interface goes in, like the platform's own auto-configuration does:
      // what belongs to a workflow module as a whole is the core's duty, and a
      // deployment service built without the interface skips all of it
      final var deploymentService = new DeploymentService(
          properties, deploymentServices, wiringServices, workflowTaskWiring);

      return new SpringBootDeploymentService(
          deploymentService, allWorkflowModules, processServices);

    }

  }

  @Test
  public void testDeploymentAndStartProcessing(
      final CapturedOutput output) throws IOException {

    try (var testApp = SpringBootTestApplication.builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml")
        .addResource("test-module/processes/dummy/DummyProcess.bpmn")
        .addResource("test-module/processes/dummy/DummyDecision.dmn")
        .hideResource("META-INF/workflow-module")
        .build(); var context = testApp.applicationBuilder(
            DummyAdapterConfiguration.class,
            DummyAdapterProcessServiceConfiguration.class,
            DummyExtensionWiringConfiguration.class,
            WorkflowModuleAutoConfiguration.class,
            SpringBootMigrationAdapterAutoConfiguration.class,
            TestPersistenceConfiguration.class, TestPhaseTwoOutboxConfiguration.class,
            TestTransactionRunnerConfiguration.class,
            SampleWorkflowService.class,
            WorkflowModuleConfiguration.class,
            TestConfig.class)
            .run()) {

      final var capturedOutput = output.getAll();

      final var readBpmn = "Dummy-Adapter[test]: Reading BPMN 'DummyProcess.bpmn' for test-module";
      final var prepareBpmn = "Dummy-Adapter[test]: Preparing BPMN 'DummyProcess.bpmn' for test-module";
      final var adapterWiring = "Dummy-Adapter[test]: Wiring BPMN process 'DummyProcess' for test-module";
      final var extensionWiring = "Dummy-Extension: Wiring BPMN for test-module";
      final var readDmn = "Dummy-Adapter[test]: Reading DMN 'DummyDecision.dmn' for test-module";
      final var deployResources = "Dummy-Adapter[test]: Deploying resources for test-module";
      final var appStarted = "seconds (process running for";
      final var adapterStartProcessing = "Dummy-Adapter[test]: Starting workflow processing for test-module";
      final var extensionStartProcessing = "Dummy-Extension: Starting workflow processing for test-module";

      final var readBpmnPos = capturedOutput.indexOf(readBpmn);
      final var prepareBpmnPos = capturedOutput.indexOf(prepareBpmn);
      final var adapterWiringPos = capturedOutput.indexOf(adapterWiring);
      final var extensionWiringPos = capturedOutput.indexOf(extensionWiring);
      final var readDmnPos = capturedOutput.indexOf(readDmn);
      final var deployResourcesPos = capturedOutput.indexOf(deployResources);
      final var appStartedPos = capturedOutput.indexOf(appStarted);
      final var adapterStartProcessingPos = capturedOutput.indexOf(adapterStartProcessing);
      final var extensionStartProcessingPos = capturedOutput.indexOf(extensionStartProcessing);

      Assertions.assertTrue(readBpmnPos >= 0,
          "Expected '"
              + readBpmn
              + "'. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(prepareBpmnPos >= 0,
          "Expected '"
              + prepareBpmn
              + "'. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(adapterWiringPos >= 0,
          "Expected '"
              + adapterWiring
              + "'. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(extensionWiringPos >= 0,
          "Expected '"
              + extensionWiring
              + "'. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(deployResourcesPos >= 0,
          "Expected '"
              + deployResources
              + "'. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(appStartedPos >= 0,
          "Expected '"
              + appStarted
              + "'. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(adapterStartProcessingPos >= 0,
          "Expected '"
              + adapterStartProcessing
              + "'. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(extensionStartProcessingPos >= 0,
          "Expected '"
              + extensionStartProcessing
              + "'. Captured output: "
              + capturedOutput);

      Assertions.assertTrue(readBpmnPos < prepareBpmnPos,
          "Expected reading BPMN before preparing. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(prepareBpmnPos < adapterWiringPos,
          "Expected preparing BPMN before adapter wiring. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(adapterWiringPos < extensionWiringPos,
          "Expected adapter wiring before extension wiring. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(extensionWiringPos < deployResourcesPos,
          "Expected extension wiring before deploying resources. Captured output: "
              + capturedOutput);
      // the decision a business rule task calls belongs to the module and is deployed
      // with it - read after the processes, because the context it is added to is what
      // reading a process produced
      Assertions.assertTrue(extensionWiringPos < readDmnPos,
          "Expected the DMN of the module to be read after its processes were wired. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(readDmnPos < deployResourcesPos,
          "Expected the DMN to be read before deploying resources. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(deployResourcesPos < appStartedPos,
          "Expected deploying resources before app started. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(appStartedPos < adapterStartProcessingPos,
          "Expected app started before adapter starts processing. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(adapterStartProcessingPos < extensionStartProcessingPos,
          "Expected adapter starts processing before extension. Captured output: "
              + capturedOutput);

    }

    // after closing the context (graceful shutdown), workflow processing has to be
    // stopped in reverse start order: extensions first, then adapters
    final var capturedOutput = output.getAll();

    final var extensionStopProcessing = "Dummy-Extension: Stopping workflow processing for test-module";
    final var adapterStopProcessing = "Dummy-Adapter[test]: Stopping workflow processing for test-module";

    final var extensionStopProcessingPos = capturedOutput.indexOf(extensionStopProcessing);
    final var adapterStopProcessingPos = capturedOutput.indexOf(adapterStopProcessing);

    Assertions.assertTrue(extensionStopProcessingPos >= 0,
        "Expected '"
            + extensionStopProcessing
            + "'. Captured output: "
            + capturedOutput);
    Assertions.assertTrue(adapterStopProcessingPos >= 0,
        "Expected '"
            + adapterStopProcessing
            + "'. Captured output: "
            + capturedOutput);
    Assertions.assertTrue(extensionStopProcessingPos < adapterStopProcessingPos,
        "Expected extension stops processing before adapter (reverse start order). Captured output: "
            + capturedOutput);

  }

}
