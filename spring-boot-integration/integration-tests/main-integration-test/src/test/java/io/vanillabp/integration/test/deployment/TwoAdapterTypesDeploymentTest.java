package io.vanillabp.integration.test.deployment;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.AdapterConfigurationBase;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.BpmnParseException;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.deployment.DeploymentAutoConfiguration;
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
import lombok.extern.slf4j.Slf4j;

/**
 * Regression test for review finding B1: two different adapter TYPES must coexist in
 * one application - THE migration scenario. The dummy adapter plus a second
 * dummy-like adapter type ("dummy2", defined inline) boot together, both deployment
 * services receive {@code deployResources} and both process services are found by
 * the election (proven by a green boot - the election fails fast on any prioritized
 * adapter without a process service).
 * <p>
 * Historically each adapter registered a bean of type
 * {@code List<AdapterDeploymentService>}; Spring's collection injection only
 * collects <i>element</i> beans, so a second adapter type broke the boot.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TwoAdapterTypesDeploymentTest {

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
            type: dummy2
        workflow-modules:
          test-module:
            adapters:
              test:
                resources-location: classpath*:test-module/processes/dummy
              test2:
                resources-location: classpath*:test-module/processes/dummy
      test-module:
        nothing: there
      """;

  /**
   * Announces the second adapter type (mirrors {@link DummyAdapterConfiguration}).
   */
  public static class SecondAdapterConfiguration extends AdapterConfigurationBase {

    public static final String ADAPTER_TYPE = "dummy2";

    @Override
    public String getAdapterType() {
      return ADAPTER_TYPE;
    }

  }

  /**
   * Log-only deployment service of the second adapter type.
   */
  @Slf4j
  public static class SecondDeploymentService implements AdapterDeploymentService<Object, Object> {

    @Override
    public Class<Object> getModelType() {
      return Object.class;
    }

    @Override
    public Class<Object> getProcessContextType() {
      return Object.class;
    }

    @Override
    public String getAdapterId() {
      return "test2";
    }

    @Override
    public String getAdapterType() {
      return SecondAdapterConfiguration.ADAPTER_TYPE;
    }

    @Override
    public List<Map.Entry<String, Object>> readBpmn(
        final String workflowModuleId,
        final String filename,
        final InputStream bpmn,
        final boolean isVanillaBpBpmn) throws BpmnParseException {
      return List.of(Map.entry("DummyProcess", new Object()));
    }

    @Override
    public Object prepareBpmn(
        final String workflowModuleId,
        final Object existingContext,
        final String filename,
        final String bpmnProcessId,
        final Object model) {
      return new Object();
    }

    @Override
    public void wireBpmn(
        final String workflowModuleId,
        final String filename,
        final String bpmnProcessId,
        final Object model,
        final Object context) {
      // nothing to wire
    }

    @Override
    public void deployResources(
        final String workflowModuleId,
        final Object bpmsProcessingContext) throws IllegalStateException {
      log.info("Dummy2-Adapter: Deploying resources for {}", workflowModuleId);
    }

    @Override
    public void startWorkflowProcessing(
        final String workflowModuleId,
        final Object bpmsProcessingContext) {
      log.info("Dummy2-Adapter: Starting workflow processing for {}", workflowModuleId);
    }

  }

  /**
   * Log-only process service of the second adapter type serving adapter id 'test2'.
   */
  public static class SecondProcessService implements MigratableProcessService<Object> {

    @Override
    public String getAdapterId() {
      return "test2";
    }

    /**
     * Serves every operation and does nothing: this double is here for the election
     * and the deployment, not for what an operation sends to a BPMS.
     */
    @Override
    public java.util.Map<io.vanillabp.integration.spi.PhaseOperation, io.vanillabp.integration.adapter.spi.PhaseOperationHandler<Object>> phaseOperations() {
      final var operations = new java.util.HashMap<io.vanillabp.integration.spi.PhaseOperation, io.vanillabp.integration.adapter.spi.PhaseOperationHandler<Object>>();
      io.vanillabp.integration.spi.PhaseOperation.CORE_OPERATIONS
          .forEach(
              operation -> operations
                  .put(
                      operation,
                      io.vanillabp.integration.adapter.spi.PhaseOperationHandler
                          .of(request -> {
                          }, request -> {
                          })));
      return operations;
    }

    @Override
    public WorkflowAwareness awarenessOfTask(
        final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId) {
      return WorkflowAwareness.UNKNOWN_TO_BPMS;
    }

    @Override
    public WorkflowAwareness awarenessOfWorkflow(
        final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
        final io.vanillabp.integration.spi.AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId) {
      return WorkflowAwareness.UNKNOWN_TO_BPMS;
    }

    @Override
    public io.vanillabp.integration.adapter.spi.WorkflowAwareness awarenessOfUserTask(
        final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId) {
      return io.vanillabp.integration.adapter.spi.WorkflowAwareness.UNKNOWN_TO_BPMS;
    }

  }

  /**
   * Element beans of the second adapter - one AdapterDeploymentService and one
   * MigratableProcessService bean, following the element-bean convention.
   */
  @Configuration
  public static class SecondAdapterBeans {

    @Bean
    public SecondDeploymentService secondDeploymentService() {
      return new SecondDeploymentService();
    }

    @Bean
    public SecondProcessService secondProcessService() {
      return new SecondProcessService();
    }

  }

  @Test
  public void twoAdapterTypesBootTogether(
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
            SecondAdapterConfiguration.class,
            SecondAdapterBeans.class,
            WorkflowModuleAutoConfiguration.class,
            SpringBootMigrationAdapterAutoConfiguration.class,
            DeploymentAutoConfiguration.class,
            TestPersistenceConfiguration.class, TestPhaseTwoOutboxConfiguration.class,
            TestTransactionRunnerConfiguration.class,
            SampleWorkflowService.class,
            WorkflowModuleConfiguration.class)
            .run()) {

      Assertions.assertTrue(context.isActive(), "context should boot with two adapter types");

      final var capturedOutput = output.getAll();

      // both adapters' deployment services have to receive deployResources
      Assertions.assertTrue(
          capturedOutput.contains("Dummy-Adapter[test]: Deploying resources for test-module"),
          "dummy adapter has to deploy. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(
          capturedOutput.contains("Dummy2-Adapter: Deploying resources for test-module"),
          "dummy2 adapter has to deploy. Captured output: "
              + capturedOutput);

    }

  }

}
