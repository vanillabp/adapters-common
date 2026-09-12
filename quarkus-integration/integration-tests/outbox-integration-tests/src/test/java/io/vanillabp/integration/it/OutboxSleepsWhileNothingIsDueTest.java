package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.CountingPoolInterceptor;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * What a quiet application costs on the JDBC store, counted in connections rather than
 * measured in seconds. A poll is a select plus a delete whether or not anything is waiting,
 * and an application sitting in a timer used to pay for them every ten seconds.
 * <p>
 * Connections and not statements, because a connection is the claim from below: none taken is
 * none used, whatever the code would have sent over it.
 * <p>
 * The cap is an hour here, so anything which happens sooner can only come from the store
 * itself saying when it is due, or from the notification after a commit.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OutboxSleepsWhileNothingIsDueTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addClass(CountingPoolInterceptor.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideRuntimeConfigKey("vanillabp.outbox.poll-interval", "PT1H")
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:outbox-sleeping-it;DB_CLOSE_DELAY=-1");

  @Inject
  WorkflowService workflowService;

  @Inject
  RecordingPhaseTwoListener listener;

  @Inject
  UserTransaction userTransaction;

  @Inject
  DataSource dataSource;

  @BeforeEach
  public void reset() {

    listener.reset();

  }

  private Aggregate startAWorkflow(
      final String content) throws Exception {

    userTransaction.begin();
    try {
      final var aggregate = workflowService.startWorkflow(content);
      userTransaction.commit();
      return aggregate;
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

  }

  /**
   * Waits until the whole table holds nothing which is still owed. The whole table and not
   * only this test's aggregate: an entry of an earlier test method which is still open would
   * be work the poller legitimately wakes up for, and the count below would then measure that
   * instead of the sleep.
   */
  private void awaitNothingLeftUndone() throws Exception {

    final var deadline = System.currentTimeMillis() + 30_000;
    while (countOpenEntries() > 0) {
      assertTrue(System.currentTimeMillis() < deadline, "an entry of the outbox was never dispatched");
      Thread.sleep(50);
    }

  }

  private long countOpenEntries() throws Exception {

    try (var connection = dataSource.getConnection(); var statement = connection
        .createStatement(); var resultSet = statement
            .executeQuery("SELECT COUNT(*) FROM VANILLABP_PHASE_TWO_OUTBOX WHERE STATUS = 'OPEN'")) {
      resultSet.next();
      return resultSet.getLong(1);
    }

  }

  @Test
  @DisplayName("No connection is taken while the store owes nothing")
  public void aQuietStoreIsAskedNothing() throws Exception {

    startAWorkflow("quiet-store");
    awaitNothingLeftUndone();
    // the reads above prove that the counter sees a connection at all, which is what makes
    // the zero below a measurement rather than a silence
    assertTrue(CountingPoolInterceptor.acquired() > 0);

    CountingPoolInterceptor.forgetWhatWasAcquired();
    Thread.sleep(3000);

    assertEquals(
        0L,
        CountingPoolInterceptor.acquired(),
        "a store with nothing to do must not take a connection, and a connection is what every "
            + "statement needs");

  }

  @Test
  @DisplayName("The questions the poller asks are answered from an index")
  public void theQuestionsOfThePollerAreIndexed() throws Exception {

    // without these the aggregate asking when the next entry is due reads the whole table, and
    // that cost grows with everything the table ever held while the wake-ups stay as rare
    final var indexed = new java.util.LinkedHashMap<String, java.util.List<String>>();
    try (var connection = dataSource.getConnection(); var resultSet = connection
        .getMetaData()
        .getIndexInfo(null, null, "VANILLABP_PHASE_TWO_OUTBOX", false, true)) {
      while (resultSet.next()) {
        final var name = resultSet.getString("INDEX_NAME");
        if (name != null) {
          indexed
              .computeIfAbsent(name.toUpperCase(), index -> new java.util.ArrayList<>())
              .add(resultSet.getString("COLUMN_NAME"));
        }
      }
    }

    assertEquals(
        java.util.List.of("STATUS", "NEXT_ATTEMPT_AT"),
        indexed.get("VANILLABP_PHASE_TWO_OUTBOX_DUE"),
        "the due question and the select which picks the entries up read these two: "
            + indexed);
    assertEquals(
        java.util.List.of("STATUS", "DONE_AT"),
        indexed.get("VANILLABP_PHASE_TWO_OUTBOX_AGE"),
        "the retention question and its delete read these two: "
            + indexed);

  }

  @Test
  @DisplayName("The commit still dispatches at once, an hour of cap notwithstanding")
  public void theCommitStillDispatchesAtOnce() throws Exception {

    final var startedAt = System.currentTimeMillis();
    startAWorkflow("fast-path");

    listener.awaitInvocations(1, 10_000);
    assertTrue(
        (System.currentTimeMillis() - startedAt) < 10_000,
        "with a cap of an hour, only the notification after the commit can explain a dispatch this "
            + "soon - and that notification is what every VanillaBP application always had");

  }

  @Test
  @DisplayName("An entry which is due again in half a second does not wait out the cap")
  public void anEntryDueSoonShortensALongSleep() throws Exception {

    // the first dispatch fails, so the store writes the next attempt one
    // 'attempt-frequency' away - half a second here, against an hour of cap
    listener.failNextDispatches(1);

    startAWorkflow("retry-soon");

    listener.awaitInvocations(2, 20_000);

  }

}
