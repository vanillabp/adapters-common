package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.bson.Document;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.runtime.processservice.PlatformDefaultStore;
import io.vanillabp.integration.runtime.processservice.QuarkusPersistenceTechnology;
import io.vanillabp.integration.test.mixed.JpaAggregate;
import io.vanillabp.integration.test.mixed.JpaWorkflowService;
import io.vanillabp.integration.test.mixed.MixedTaskWiringSource;
import io.vanillabp.integration.test.mixed.MongoAggregate;
import io.vanillabp.integration.test.mixed.MongoWorkflowService;
import io.vanillabp.integration.test.mixed.OutboxUsingExtension;
import io.vanillabp.integration.test.mixed.PhaseTwoRecorder;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * An application with two persistences. One aggregate is a Hibernate ORM Panache
 * active record, the other a MongoDB Panache active record, so both platform defaults of the
 * phase-two outbox and of the delivery log are active and none of them can serve both.
 * <p>
 * Such an application used not to boot at all: the startup validation resolved the
 * outbox, found two candidates and ended with a message telling the application to attribute
 * them itself. Now the store is read off the persistence VanillaBP resolved for each
 * aggregate, so this test starting at all is half of the assertion. The other half is where
 * the outbox entries went.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MixedPersistenceStoreAttributionTest {

  private static final String MONGO_DATABASE = "mixed-persistence-it";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(JpaAggregate.class)
          .addClass(JpaWorkflowService.class)
          .addClass(MongoAggregate.class)
          .addClass(MongoWorkflowService.class)
          .addClass(MixedTaskWiringSource.class)
          .addClass(OutboxUsingExtension.class)
          .addClass(PhaseTwoRecorder.class)
          .addAsResource(new StringAsset("not parsed by the dummy adapter"), "processes/dummy/JpaProcess.bpmn")
          .addAsResource(new StringAsset("not parsed by the dummy adapter"), "processes/dummy/MongoProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("dummy-adapter.at-least-once-delivery", "true")
      .overrideConfigKey("quarkus.mongodb.database", MONGO_DATABASE)
      .overrideConfigKey("vanillabp.outbox.poll-interval", "PT0.5S")
      .overrideConfigKey("vanillabp.outbox.attempt-frequency", "PT0.5S");

  @Inject
  JpaWorkflowService jpaWorkflows;

  @Inject
  MongoWorkflowService mongoWorkflows;

  @Inject
  PhaseTwoRecorder recorder;

  @Inject
  OutboxUsingExtension extension;

  @Inject
  DataSource dataSource;

  @Inject
  MongoClient mongoClient;

  @Test
  @DisplayName("Each aggregate's outbox entry is written into the store of its own persistence")
  public void eachAggregateUsesTheStoreOfItsPersistence() throws Exception {

    QuarkusTransaction
        .requiringNew()
        .run(() -> jpaWorkflows.startWorkflow("jpa-start"));
    QuarkusTransaction
        .requiringNew()
        .run(() -> mongoWorkflows.startWorkflow("mongo-start"));

    assertTrue(
        recorder.awaitStartedWorkflow("jpa-start", 30_000),
        "phase two of the relational aggregate never arrived");
    assertTrue(
        recorder.awaitStartedWorkflow("mongo-start", 30_000),
        "phase two of the MongoDB aggregate never arrived");

    assertEquals(
        List.of("jpa-start"),
        aggregateIdsInJdbcOutbox(),
        "the relational outbox holds exactly the entry of the relational aggregate");
    assertEquals(
        List.of("mongo-start"),
        aggregateIdsInMongoOutbox(),
        "the MongoDB outbox holds exactly the entry of the MongoDB aggregate");

  }

  @Test
  @DisplayName("An extension is told the same store the workflow's own entries go into")
  public void anExtensionResolvesTheStoreOfTheAggregate() {

    final var relational = extension.outboxOf(JpaAggregate.class);
    final var mongo = extension.outboxOf(MongoAggregate.class);

    assertEquals(
        QuarkusPersistenceTechnology.Technology.JPA,
        ((PlatformDefaultStore) relational).technology(),
        "the relational aggregate is served by the relational outbox");
    assertEquals(
        QuarkusPersistenceTechnology.Technology.MONGO,
        ((PlatformDefaultStore) mongo).technology(),
        "the MongoDB aggregate is served by the MongoDB outbox");

  }

  private List<String> aggregateIdsInJdbcOutbox() throws Exception {

    final var ids = new ArrayList<String>();
    try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
        "SELECT AGGREGATE_ID FROM VANILLABP_PHASE_TWO_OUTBOX ORDER BY AGGREGATE_ID"); var resultSet = statement
            .executeQuery()) {
      while (resultSet.next()) {
        ids.add(resultSet.getString(1));
      }
    }
    return ids;

  }

  private List<String> aggregateIdsInMongoOutbox() {

    final var ids = new ArrayList<String>();
    for (final Document document : mongoClient
        .getDatabase(MONGO_DATABASE)
        .getCollection("vanillabp-phase-two-outbox")
        .find()) {
      ids.add(document.getString("aggregateId"));
    }
    ids.sort(String::compareTo);
    return ids;

  }

}
