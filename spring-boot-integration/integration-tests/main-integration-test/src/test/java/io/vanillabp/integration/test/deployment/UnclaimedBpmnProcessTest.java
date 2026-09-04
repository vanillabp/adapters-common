package io.vanillabp.integration.test.deployment;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import io.vanillabp.adapter.dummy.springboot.DummyAdapterConfiguration;
import io.vanillabp.adapter.dummy.springboot.deployment.DummyTaskWiringSource;
import io.vanillabp.adapter.dummy.springboot.processservice.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.TestTransactionRunnerConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import lombok.Getter;
import lombok.Setter;

/**
 * A BPMN file carries two executable processes - which is what a modeller produces by
 * drawing a called process next to the calling one, and what a migration leaves behind
 * when a process moves out of an application while its model stays in the file. The file
 * travels to the BPMS as a whole, so both processes are deployed, and the second one is
 * validated against the <code>&#64;WorkflowTask</code> methods of a workflow service
 * which does not exist. That used to end the boot with a message asking for a workflow
 * service, which is the right sentence for a process the application means to serve and
 * the wrong one for a process it does not.
 * <p>
 * Now the boot continues and says what such a process costs. What did NOT change is the
 * case which really is a defect: a process a workflow service DOES claim and whose task
 * has no method still ends the boot, and so does a method matching no task of any process
 * of the module.
 */
@ExtendWith(SuppressOutputExtension.class)
public class UnclaimedBpmnProcessTest {

  private static final String MODULE = "test-module";

  /**
   * The process of the file this application serves. Its neighbour <code>Called</code>
   * is served by nobody.
   */
  @Service
  @WorkflowService(
      workflowAggregateClass = CallingAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = "Calling"))
  public static class CallingWorkflowService {

    @WorkflowTask
    public void juhu(
        final CallingAggregate aggregate) {

      aggregate.setStatus("processed");

    }

  }

  /**
   * Serves <code>Calling</code> and declares a second method for a task nobody
   * modelled - the reverse direction of the validation, which the unclaimed process next
   * to it must not make quieter.
   */
  @Service
  @WorkflowService(
      workflowAggregateClass = CallingAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = "Calling"))
  public static class OrphanMethodCallingWorkflowService {

    @WorkflowTask
    public void juhu(
        final CallingAggregate aggregate) {

      aggregate.setStatus("processed");

    }

    @WorkflowTask(taskDefinition = "activityNobodyModelled")
    public void typo() {

    }

  }

  @Getter
  @Setter
  public static class CallingAggregate {

    private String id;

    private String status;

  }

  /**
   * What the file really holds: the two executable processes and their tasks. Only
   * <code>Calling</code> is claimed, and the task of <code>Called</code> is a service
   * task - the non-optional kind, which is what used to end the boot.
   */
  @Configuration
  static class TwoProcessesConfiguration {

    static final Map<String, CallingAggregate> AGGREGATES = new ConcurrentHashMap<>();

    @Bean
    DummyTaskWiringSource twoProcessesWiringSource() {

      return new DummyTaskWiringSource() {

        @Override
        public List<String> executableProcessesOf(
            final String adapterId,
            final String workflowModuleId,
            final String filename) {

          return List.of("Calling", "Called");

        }

        @Override
        public List<BpmnTaskSpec> tasksOf(
            final String adapterId,
            final String workflowModuleId,
            final String bpmnProcessId) {

          return switch (bpmnProcessId) {
            case "Calling" -> List.of(new BpmnTaskSpec("Activity_Juhu", "juhu"));
            case "Called" -> List.of(new BpmnTaskSpec("Activity_Called", "doTheCalledWork"));
            default -> List.of();
          };

        }

      };

    }

    /**
     * Saved and read back, so the task of the claimed process can be invoked - the
     * proof that the process next to the unclaimed one is wired as before.
     */
    @Bean
    AggregatePersistenceAware<CallingAggregate> callingAggregatePersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<CallingAggregate> getAggregateClass() {
          return CallingAggregate.class;
        }

        @Override
        public CallingAggregate save(
            final CallingAggregate aggregate) {
          AGGREGATES.put(aggregate.getId(), aggregate);
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final CallingAggregate aggregate) {
          return aggregate.getId();
        }

        @Override
        public Class<?> getAggregateIdType() {
          return String.class;
        }

        @Override
        public CallingAggregate loadById(
            final Object aggregateId) {
          return AGGREGATES.get(aggregateId);
        }

      };

    }

  }

  /**
   * The same file, but the CLAIMED process has a task no method serves - the defect
   * which still ends the boot.
   */
  @Configuration
  static class ClaimedProcessMissingAHandlerConfiguration {

    @Bean
    DummyTaskWiringSource claimedProcessMissingAHandlerWiringSource() {

      return new DummyTaskWiringSource() {

        @Override
        public List<String> executableProcessesOf(
            final String adapterId,
            final String workflowModuleId,
            final String filename) {

          return List.of("Calling", "Called");

        }

        @Override
        public List<BpmnTaskSpec> tasksOf(
            final String adapterId,
            final String workflowModuleId,
            final String bpmnProcessId) {

          return switch (bpmnProcessId) {
            case "Calling" -> List.of(
                new BpmnTaskSpec("Activity_Juhu", "juhu"),
                new BpmnTaskSpec("Activity_Unknown", "notImplemented"));
            case "Called" -> List.of(new BpmnTaskSpec("Activity_Called", "doTheCalledWork"));
            default -> List.of();
          };

        }

      };

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
                resources-location: classpath*:test-module/processes/unclaimed
      """;

  private SpringBootTestApplication buildTestApp() throws IOException {

    return SpringBootTestApplication
        .builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", APPLICATION_YAML)
        .addResource("test-module/processes/unclaimed/CallingAndCalled.bpmn")
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build();

  }

  private ConfigurableApplicationContext runTestApplication(
      final SpringBootTestApplication testApp,
      final Class<?>... scenarioClasses) {

    final var classes = new java.util.LinkedList<Class<?>>(List.of(
        DummyAdapterConfiguration.class,
        DummyAdapterProcessServiceConfiguration.class,
        WorkflowModuleAutoConfiguration.class,
        SpringBootMigrationAdapterAutoConfiguration.class,
        TestPersistenceConfiguration.class,
        TestPhaseTwoOutboxConfiguration.class,
        TestTransactionRunnerConfiguration.class,
        WorkflowModuleConfiguration.class,
        DeploymentTest.TestConfig.class));
    classes.addAll(List.of(scenarioClasses));
    return testApp.applicationBuilder(classes.toArray(Class[]::new)).run();

  }

  @Test
  @DisplayName("The process nobody serves is a WARN, and the one next to it works")
  public void anUnclaimedProcessBootsWithAGuidingWarn(
      final CapturedOutput output) throws IOException {

    TwoProcessesConfiguration.AGGREGATES.clear();

    // what the other tests of this class already wrote: all of them boot the same
    // module, so only the output of THIS boot answers the question
    final var writtenBeforeThisBoot = output.getAll().length();

    try (var testApp = buildTestApp(); var context = runTestApplication(
        testApp,
        CallingWorkflowService.class,
        TwoProcessesConfiguration.class)) {

      final var captured = output.getAll().substring(writtenBeforeThisBoot);
      Assertions.assertTrue(
          captured.contains("process 'Called' of file 'CallingAndCalled.bpmn'"),
          "expected the WARN to name the unclaimed process and its file but got: "
              + captured);
      Assertions.assertTrue(
          captured.contains("Workflow module 'test-module' deploys BPMN processes which no @WorkflowService class"),
          "expected the WARN to name the workflow module but got: "
              + captured);
      Assertions.assertTrue(
          captured.contains("not get past its first task"),
          "expected the WARN to say what such a process costs but got: "
              + captured);
      Assertions.assertTrue(
          captured.contains("take the process out of its file"),
          "expected the WARN to name both ways out but got: "
              + captured);
      Assertions.assertFalse(
          captured.contains("- process 'Calling' of file"),
          "the claimed process of the same file is not reported: "
              + captured);

      // the claimed process is wired as before: its task runs through the core
      final var aggregate = new CallingAggregate();
      aggregate.setId("4711");
      aggregate.setStatus("new");
      TwoProcessesConfiguration.AGGREGATES.put("4711", aggregate);
      final var dummyAdapter = context.getBean(
          "DummyAdapter_DeploymentService_test",
          io.vanillabp.adapter.dummy.springboot.deployment.DeploymentService.class);
      final var outcome = dummyAdapter.invokeTask(MODULE, "Calling", new TaskInvocationContext() {

        @Override
        public String getTaskDefinition() {
          return "juhu";
        }

        @Override
        public String getWorkflowAggregateId() {
          return "4711";
        }

      });
      Assertions.assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, outcome.kind());
      Assertions.assertEquals("processed", TwoProcessesConfiguration.AGGREGATES.get("4711").getStatus());

    }

  }

  @Test
  @DisplayName("A task of the CLAIMED process without a method still ends the boot")
  public void aClaimedProcessMissingAHandlerStillFails() throws IOException {

    try (var testApp = buildTestApp()) {

      final var failure = Assertions.assertThrows(
          Exception.class,
          () -> runTestApplication(
              testApp,
              CallingWorkflowService.class,
              ClaimedProcessMissingAHandlerConfiguration.class).close());

      final var message = rootMessageOf(failure);
      Assertions.assertTrue(
          message.contains("Task wiring of BPMN process 'Calling' of workflow module 'test-module' is incomplete"),
          message);
      Assertions.assertTrue(message.contains("'Activity_Unknown'"), message);
      Assertions.assertTrue(
          message.contains("@WorkflowTask(taskDefinition = \"notImplemented\")"),
          message);
      Assertions.assertTrue(message.contains(CallingWorkflowService.class.getName()), message);

    }

  }

  @Test
  @DisplayName("A method matching no task still ends the boot, unclaimed process or not")
  public void anOrphanMethodStillFailsNextToAnUnclaimedProcess() throws IOException {

    try (var testApp = buildTestApp()) {

      final var failure = Assertions.assertThrows(
          Exception.class,
          () -> runTestApplication(
              testApp,
              OrphanMethodCallingWorkflowService.class,
              TwoProcessesConfiguration.class).close());

      final var message = rootMessageOf(failure);
      Assertions.assertTrue(message.contains("activityNobodyModelled"), message);
      Assertions.assertTrue(message.contains("fix the annotation"), message);

    }

  }

  /**
   * The guiding message of a failing boot arrives wrapped: Spring runs the deployment
   * while it refreshes the context.
   */
  private String rootMessageOf(
      final Throwable failure) {

    var cause = failure;
    while ((cause.getCause() != null) && !(cause instanceof IllegalStateException)) {
      cause = cause.getCause();
    }
    return String.valueOf(cause.getMessage());

  }

}
