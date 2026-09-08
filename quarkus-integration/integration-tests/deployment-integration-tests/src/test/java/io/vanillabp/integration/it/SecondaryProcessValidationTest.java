package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.logging.Level;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Getter;
import lombok.Setter;

/**
 * What a workflow service declares is more than one BPMN process id, and everything
 * configurable per workflow is configurable for each of them: its prioritized adapters, its
 * outbox, what its leftovers were persisted under. The startup validations used to run for
 * the PRIMARY process only, so the id a rename leaves behind - the one whose workflows are
 * still out there - was the one nobody looked at.
 * <p>
 * The check measured here is the one with the sharpest failure mode, and it is the same one
 * the Spring Boot integration measures: an aggregate whose persisted adapter id names an
 * adapter the configuration dropped is found at the first operation instead of at boot, and
 * for a renamed process the leftovers sit under the SECONDARY id.
 */
@ExtendWith(SuppressOutputExtension.class)
public class SecondaryProcessValidationTest {

  static final String DEPLOYED_PROCESS = "OrderApproval";

  /**
   * The id the rename left behind: declared, with no model of this boot under it.
   */
  static final String OLD_PROCESS = "order_approval";

  static final String UNCONFIGURED_ADAPTER = "retired-bpms";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("secondary-process/application.yaml", "application.yaml")
          .addClass(RenamedAggregate.class)
          .addClass(RenamedAggregatePersistence.class)
          .addClass(RenamedWorkflowService.class)
          .addClass(OutboxHoldingCallsOfTheOldProcess.class)
          .addAsResource(
              new StringAsset("not parsed by the dummy adapter"), "processes/dummy/OrderApproval.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .setLogRecordPredicate(record -> record.getLevel().intValue() >= Level.WARNING.intValue())
      .assertLogRecords(records -> {
        final var messages = records
            .stream()
            .map(record -> record.getMessage() == null
                ? ""
                : String.format(record.getMessage(), record.getParameters()))
            .toList();
        final var reports = messages
            .stream()
            .filter(message -> message
                .contains("The adapter id '%s' is NOT configured any more".formatted(UNCONFIGURED_ADAPTER)))
            .toList();
        assertEquals(
            1,
            reports.size(),
            () -> "the leftovers of the secondary BPMN process were not reported: "
                + messages);
        final var warning = reports.getFirst();
        assertTrue(warning.contains("BPMN process '%s'".formatted(OLD_PROCESS)), warning);
        assertTrue(warning.contains("waiting phase-two outbox entries"), warning);
      });

  @Inject
  ProcessService<RenamedAggregate> processService;

  @Test
  @DisplayName("The application boots and its ProcessService is the one of the deployed id")
  public void theApplicationBootsWithBothIdsValidated() {

    // the report itself is asserted on the boot's log records; a validation which now runs
    // for a further id must not change what the primary one is
    assertEquals(
        DEPLOYED_PROCESS,
        ((io.vanillabp.integration.runtime.processservice.ProcessServiceBaseCdiBean<RenamedAggregate>) processService)
            .getBpmnProcessId());

  }

  @Getter
  @Setter
  public static class RenamedAggregate {

    private String id;

  }

  @ApplicationScoped
  public static class RenamedAggregatePersistence implements AggregatePersistenceAware<RenamedAggregate> {

    @Override
    public Class<RenamedAggregate> getAggregateClass() {

      return RenamedAggregate.class;

    }

  }

  /**
   * The application after a rename: the new id is what this boot deploys, the old one is
   * declared so the workflows still running under it keep being served.
   */
  @ApplicationScoped
  @WorkflowService(
      workflowAggregateClass = RenamedAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = DEPLOYED_PROCESS),
      secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = OLD_PROCESS))
  public static class RenamedWorkflowService {
  }

  /**
   * An outbox holding calls of the OLD id which were planned by an adapter the configuration
   * does not know any more - what a migration abandoned halfway leaves behind, under the id a
   * rename left behind.
   */
  @ApplicationScoped
  public static class OutboxHoldingCallsOfTheOldProcess implements PhaseTwoOutbox {

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

  }

}
