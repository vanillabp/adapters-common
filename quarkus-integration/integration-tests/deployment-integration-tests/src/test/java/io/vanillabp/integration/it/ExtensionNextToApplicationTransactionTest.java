package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.extension.ApplicationUnitOfWork;
import io.vanillabp.integration.test.extension.EveryWorkflowRunsHere;
import io.vanillabp.integration.test.extension.NoteAggregate;
import io.vanillabp.integration.test.extension.NoteAggregatePersistence;
import io.vanillabp.integration.test.extension.NoteWorkflowService;
import io.vanillabp.integration.test.extension.TransactionUsingExtension;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * An application which brings a transaction runner of its own, with an extension next to
 * it. Two things have to hold at once here, and they used to contradict each other.
 * <p>
 * The application BOOTS: the platform's own runner is a CDI bean as well, and every
 * injection point of type <code>TransactionRunner</code> would be ambiguous if that bean
 * carried the SPI type. It does not, because the producer restricts it to its concrete
 * class.
 * <p>
 * And the extension writes where the workflow writes: it asks the resolver per aggregate
 * class and is handed the runner the APPLICATION contributed, not the platform's JTA.
 * An extension injecting a runner directly would have got the wrong one or nothing at
 * all, which is why the resolver is the contract.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ExtensionNextToApplicationTransactionTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("extension/application.yaml", "application.yaml")
          .addClass(NoteAggregate.class)
          .addClass(NoteAggregatePersistence.class)
          .addClass(NoteWorkflowService.class)
          .addClass(EveryWorkflowRunsHere.class)
          .addClass(TransactionUsingExtension.class)
          .addClass(ApplicationUnitOfWork.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/NoteProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  TransactionUsingExtension transactions;

  @Inject
  ApplicationUnitOfWork applicationUnitOfWork;

  @Test
  @DisplayName("The extension is handed the runner the application contributed")
  public void theExtensionWritesThroughTheApplicationsRunner() {

    assertEquals(
        "the TransactionRunner bean '%s' of the application".formatted(ApplicationUnitOfWork.class.getName()),
        transactions.describeResolutionFor(NoteAggregate.class));

    final var before = applicationUnitOfWork.getInCurrentCalls();
    final var written = transactions
        .runnerOf(NoteAggregate.class)
        .inCurrent(() -> "what the extension writes");

    assertEquals("what the extension writes", written);
    assertEquals(before + 1, applicationUnitOfWork.getInCurrentCalls(), "the application's runner ran it");

  }

  @Test
  @DisplayName("The workflow's own writes go through the same runner")
  public void theWorkflowUsesTheSameRunner() {

    // what makes the answer worth having: the entry of the extension and the workflow
    // aggregate it belongs to are committed by one unit of work, not by two
    assertSame(applicationUnitOfWork, transactions.runnerOf(NoteAggregate.class));

  }

}
