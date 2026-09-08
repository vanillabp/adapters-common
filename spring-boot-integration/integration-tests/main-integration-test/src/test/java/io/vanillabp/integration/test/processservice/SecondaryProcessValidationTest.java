package io.vanillabp.integration.test.processservice;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestTransactionRunnerConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.deployment.DeploymentTest;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * What a workflow service declares is more than one BPMN process id, and everything
 * configurable per workflow is configurable for each of them: its prioritized adapters, its
 * outbox, what its leftovers were persisted under. The startup validations used to run for
 * the PRIMARY process only, so the id a rename leaves behind - the one whose workflows are
 * still out there - was the one nobody looked at.
 * <p>
 * The check measured here is the one with the sharpest failure mode: an aggregate whose
 * persisted adapter id names an adapter the configuration dropped is found at the first
 * operation instead of at boot, and for a renamed process the leftovers sit under the
 * SECONDARY id.
 */
@ExtendWith(SuppressOutputExtension.class)
public class SecondaryProcessValidationTest {

  private static final String DEPLOYED_PROCESS = "DummyProcess";

  /**
   * The id the rename left behind: declared, with no model of this boot under it.
   */
  private static final String OLD_PROCESS = "OldDummyProcess";

  private static final String UNCONFIGURED_ADAPTER = "retired-bpms";

  public static class RenamedAggregate {

    String id = "4711";

  }

  /**
   * The application after a rename: the new id is what this boot deploys, the old one is
   * declared so the workflows still running under it keep being served.
   */
  @Service
  @WorkflowService(
      workflowAggregateClass = RenamedAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = DEPLOYED_PROCESS),
      secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = OLD_PROCESS))
  public static class RenamedWorkflowService {

    @WorkflowTask(taskDefinition = "processTask")
    public void processTask(
        final RenamedAggregate aggregate) {
    }

  }

  @Configuration
  static class AggregatePersistenceConfiguration {

    @Bean
    AggregatePersistenceAware<RenamedAggregate> renamedAggregatePersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<RenamedAggregate> getAggregateClass() {
          return RenamedAggregate.class;
        }

        @Override
        public RenamedAggregate save(
            final RenamedAggregate aggregate) {
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final RenamedAggregate aggregate) {
          return aggregate.id;
        }

      };

    }

  }

  /**
   * Stands in for the BPMN model of the deployed process: one task, which the workflow
   * service has the handler of.
   */
  @Configuration
  static class DeployedProcessWithOneTask {

    @Bean
    DummyTaskWiringSource taskWiringSource() {

      return (
          adapterId,
          workflowModuleId,
          bpmnProcessId) -> DEPLOYED_PROCESS.equals(bpmnProcessId)
              ? List.of(new BpmnTaskSpec("Activity_Process", "processTask"))
              : List.of();

    }

  }

  /**
   * An outbox holding calls of the OLD id which were planned by an adapter the
   * configuration does not know any more - what a migration abandoned halfway leaves
   * behind, under the id a rename left behind.
   */
  @Configuration
  static class OutboxHoldingCallsOfTheOldProcess {

    @Bean
    PhaseTwoOutbox outboxWithLeftoversOfTheOldProcess() {

      return new PhaseTwoOutbox() {

        @Override
        public boolean schedule(
            final PhaseTwoCall call) {
          return true;
        }

        @Override
        public Set<String> adapterIdsOfPendingCalls(
            final String workflowModuleId,
            final String bpmnProcessId) {
          return OLD_PROCESS.equals(bpmnProcessId)
              ? Set.of(UNCONFIGURED_ADAPTER)
              : Set.of();
        }

      };

    }

  }

  private SpringApplicationBuilder application() {

    return new SpringApplicationBuilder(
        DummyAdapterConfiguration.class, DummyAdapterProcessServiceConfiguration.class, WorkflowModuleAutoConfiguration.class, SpringBootMigrationAdapterAutoConfiguration.class, TestPersistenceConfiguration.class, TestTransactionRunnerConfiguration.class, WorkflowModuleConfiguration.class, DeploymentTest.TestConfig.class, AggregatePersistenceConfiguration.class, DeployedProcessWithOneTask.class, OutboxHoldingCallsOfTheOldProcess.class, RenamedWorkflowService.class);

  }

  @Test
  @DisplayName("The persisted-adapter-id check runs for a secondary BPMN process, naming it")
  public void aSecondaryProcessIsValidatedAsWell(
      final CapturedOutput output) {

    final var writtenBeforeThisBoot = output.getAll().length();

    try (var context = application().run()) {

      final var captured = output.getAll().substring(writtenBeforeThisBoot);
      Assertions.assertTrue(
          captured.contains("The adapter id '%s' is NOT configured any more".formatted(UNCONFIGURED_ADAPTER)),
          "the adapter id the outbox names was not reported at all: "
              + captured);
      Assertions.assertTrue(
          captured.contains("BPMN process '%s'".formatted(OLD_PROCESS)),
          "the report does not name the secondary BPMN process the leftovers sit under: "
              + captured);
      Assertions.assertTrue(
          captured.contains("waiting phase-two outbox entries"),
          "the report does not say what is left over: "
              + captured);

    }

  }

}
