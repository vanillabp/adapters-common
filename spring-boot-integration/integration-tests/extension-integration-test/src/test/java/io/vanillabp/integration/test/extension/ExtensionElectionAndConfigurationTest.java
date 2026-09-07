package io.vanillabp.integration.test.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.extension.sample.SampleNoteContract;
import io.vanillabp.extension.sample.SampleNoteServiceFactory;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.extension.spi.election.WorkflowElection;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The other two things an extension gets: which BPMS holds a workflow right now, and a
 * place of its own in the configuration.
 */
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
public class ExtensionElectionAndConfigurationTest {

  @Autowired
  private NotedWorkflowService workflowService;

  @Autowired
  private WorkflowElection election;

  @Autowired
  private MigrationAdapterProperties properties;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Test
  @DisplayName("The extension learns which BPMS holds the workflow")
  public void theElectionAnswersTheExtension() {

    final var aggregate = transactionTemplate.execute(status -> {
      final var started = new NotedAggregate();
      started.setContent("elected");
      return workflowService
          .getProcessService()
          .startWorkflow(started);
    });

    assertEquals(
        "the-bpms",
        workflowService
            .getNoteService()
            .bpmsHolding(aggregate));
    assertEquals(
        "the-bpms",
        election.adapterIdOfWorkflow("extension-module", "DummyProcess", aggregate.getId()));

  }

  @Test
  @DisplayName("A workflow no BPMS knows is refused with a message naming the adapters asked")
  public void anUnknownWorkflowIsRefusedGuiding() {

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> election.adapterIdOfWorkflow(
            "extension-module",
            "DummyProcess",
            TestApplication.UNKNOWN_AGGREGATE_ID));
    assertTrue(failure.getMessage().contains("the-bpms"));

  }

  @Test
  @DisplayName("A BPMN process nobody serves is refused with the workflows this application has")
  public void anUnknownWorkflowProcessIsRefusedGuiding() {

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> election.adapterIdOfWorkflow("extension-module", "NoSuchProcess", 1L));
    assertTrue(failure.getMessage().contains("extension-module/DummyProcess"));

  }

  @Test
  @DisplayName("A workflow module overrides what the extension is configured with globally")
  public void theWorkflowModuleOverridesTheGlobalSetting() {

    assertEquals(
        "Servus",
        workflowService
            .getNoteService()
            .configuredGreeting());
    assertEquals(
        "Hello",
        properties.extensionProperty(null, SampleNoteContract.EXTENSION_ID, SampleNoteServiceFactory.GREETING));
    // what the module says nothing about stays what the global section says
    assertEquals(
        "whatever",
        properties.extensionProperty("extension-module", SampleNoteContract.EXTENSION_ID, "unused"));
    assertNull(
        properties.extensionProperty("extension-module", "another-extension", SampleNoteServiceFactory.GREETING));

  }

}
