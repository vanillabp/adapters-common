package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.CountingCommandListener;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * What a quiet application costs on the MongoDB store of Quarkus, counted in commands rather
 * than measured in seconds. A poll was a claim attempt plus a retention delete whether or not
 * anything was waiting, and an application sitting in a timer paid for them every ten
 * seconds.
 * <p>
 * The cap is an hour here, so a command against the outbox collection while nothing is due
 * would have to come from a poller which ignored what its store told it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoOutboxSleepsWhileNothingIsDueTest {

  private static final String DATABASE = "outbox-sleeping-it";

  private static final String OUTBOX_COLLECTION = "vanillabp-phase-two-outbox";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addClass(CountingCommandListener.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("quarkus.mongodb.database", DATABASE)
      .overrideRuntimeConfigKey("vanillabp.outbox.poll-interval", "PT1H");

  @Inject
  WorkflowService workflowService;

  @Inject
  RecordingPhaseTwoListener listener;

  @Inject
  UserTransaction userTransaction;

  @Inject
  MongoClient mongoClient;

  private MongoCollection<Document> outbox() {

    return mongoClient
        .getDatabase(DATABASE)
        .getCollection(OUTBOX_COLLECTION);

  }

  private void awaitNothingLeftUndone() throws Exception {

    final var deadline = System.currentTimeMillis() + 30_000;
    while (outbox().countDocuments(new Document("status", "OPEN")) > 0) {
      assertTrue(System.currentTimeMillis() < deadline, "an outbox entry was never dispatched");
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("Nothing is asked of the collection while the store owes nothing")
  public void aQuietStoreIsAskedNothing() throws Exception {

    listener.reset();
    userTransaction.begin();
    workflowService.startWorkflow("quiet-store");
    userTransaction.commit();
    awaitNothingLeftUndone();
    // the silence below says nothing unless this listener can see traffic at all
    assertFalse(
        CountingCommandListener.commandsOn(OUTBOX_COLLECTION).isEmpty(),
        "dispatching an entry has to show up here, or this test measures its own instrument");

    CountingCommandListener.forgetWhatWasSent();
    Thread.sleep(3000);

    assertEquals(
        List.of(),
        CountingCommandListener.commandsOn(OUTBOX_COLLECTION),
        "a store with nothing to do has to be left alone");

  }

}
