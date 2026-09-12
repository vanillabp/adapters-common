package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import com.mongodb.ConnectionString;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * What a quiet application costs on the MongoDB store of Spring Boot, counted in commands
 * rather than measured in seconds. A poll was a claim attempt plus a retention delete
 * whether or not anything was waiting, and an application sitting in a timer paid for them
 * every ten seconds.
 * <p>
 * The cap is an hour here, so a command against the outbox collection while nothing is due
 * would have to come from a poller which ignored what its store told it.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = {
        TestApplication.class, MongoOutboxSleepsWhileNothingIsDueTest.SleepingOutboxTestConfiguration.class
    },
    properties = "vanillabp.outbox.poll-interval=PT1H")
@DirtiesContext
@Testcontainers
public class MongoOutboxSleepsWhileNothingIsDueTest {

  private static final String OUTBOX_COLLECTION = "vanillabp-phase-two-outbox";

  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse("mongo:5.0"))
      // MongoDB transactions require a replica set
      .withReplicaSet()
      .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
      .withExposedPorts(27017);

  /**
   * The listener is a bean, so the application's own client is the one which reports. A
   * client of the test's own would count nothing the application does.
   */
  static final CountingCommandListener commands = new CountingCommandListener();

  @TestConfiguration
  static class SleepingOutboxTestConfiguration {

    @Bean
    MongoClientSettingsBuilderCustomizer mongoUriCustomizer() {
      return builder -> builder
          .applyConnectionString(new ConnectionString(mongoDb.getReplicaSetUrl()))
          .addCommandListener(commands);
    }

  }

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private RecordingPhaseTwoListener listener;

  private long countEntriesNotDone() {

    return mongoTemplate
        .getCollection(OUTBOX_COLLECTION)
        .countDocuments(new org.bson.Document("status", "OPEN"));

  }

  private void awaitNothingLeftUndone() throws Exception {

    final var deadline = System.currentTimeMillis() + 30000;
    while (countEntriesNotDone() > 0) {
      assertTrue(System.currentTimeMillis() < deadline, "an outbox entry was never dispatched");
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("Nothing is asked of the collection while the store owes nothing")
  public void aQuietStoreIsAskedNothing() throws Exception {

    listener.reset();
    final var attached = transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent("quiet-store");
      return processService.startWorkflow(aggregate);
    });
    assertNotNull(attached);
    awaitNothingLeftUndone();
    // the silence below says nothing unless this listener can see traffic at all
    assertFalse(
        commands.commandsOn(OUTBOX_COLLECTION).isEmpty(),
        "dispatching an entry has to show up here, or this test measures its own instrument");

    commands.reset();
    Thread.sleep(3000);

    assertEquals(
        List.of(),
        commands.commandsOn(OUTBOX_COLLECTION),
        "a store with nothing to do has to be left alone");

  }

}
