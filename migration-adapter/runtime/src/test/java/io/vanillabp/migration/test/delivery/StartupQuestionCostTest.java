package io.vanillabp.migration.test.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.adapter.migration.scoping.NameClashAvoidanceService;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.IdentifierHeldElsewhere;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ScopedIdentifierKind;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a start asks, and what that costs - decision 19 in the repository's DECISIONS.md.
 * <p>
 * The two questions the startup checks put to the delivery log are answered from the whole
 * table, so both of them could grow with everything the application ever recorded. What
 * keeps them from doing so is not visible in the answer: the question about EXISTENCE
 * looks identical whether it transfers one row or a hundred thousand, because the code
 * reads the first row either way and the rest happens inside the driver. So this test
 * watches the statements instead of the answers, and it does so against a table which
 * holds more than one open record - the case where a missing row limit costs something.
 * <p>
 * The question about the identifiers a BPMS already holds is measured here as well, from
 * the side this repository owns. The query is the adapter's, but what comes back grows with
 * everything ever deployed into that BPMS, and the core turning it into a hundred messages
 * would cost a start as surely as a hundred statements would.
 */
@ExtendWith(SuppressOutputExtension.class)
public class StartupQuestionCostTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final int OPEN_RECORDS = 25;

  /**
   * Every statement the store prepared, with the row limit it was given.
   */
  private final List<PreparedStatementUse> statements = new ArrayList<>();

  private static class PreparedStatementUse {

    private final String sql;

    /**
     * What {@link PreparedStatement#setMaxRows(int)} was given, zero for "no limit"
     * which is the JDBC default.
     */
    private int maxRows;

    private PreparedStatementUse(
        final String sql) {

      this.sql = sql;

    }

    private String sql() {

      return sql;

    }

    private int maxRows() {

      return maxRows;

    }

    @Override
    public String toString() {

      return "%s (max rows: %d)".formatted(sql, maxRows);

    }

  }

  /**
   * A connection which reports what the store does with it. Everything is passed on to
   * H2, so the answers are the answers of a real database; only the row limit of each
   * prepared statement is noted on the way through.
   */
  private JdbcConnectionAccess watched(
      final String database) {

    return () -> {
      final var connection = DriverManager
          .getConnection("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(database), "sa", "");
      return (Connection) Proxy
          .newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[]{
                  Connection.class
      },
              connectionHandler(connection));
    };

  }

  private InvocationHandler connectionHandler(
      final Connection connection) {

    return (
        proxy,
        method,
        args) -> {
      final var answer = method.invoke(connection, args);
      if (!"prepareStatement".equals(method.getName())) {
        return answer;
      }
      final var use = new PreparedStatementUse((String) args[0]);
      statements.add(use);
      return Proxy
          .newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[]{
                  PreparedStatement.class
      },
              (
                  statementProxy,
                  statementMethod,
                  statementArgs) -> {
                if ("setMaxRows".equals(statementMethod.getName())) {
                  use.maxRows = (Integer) statementArgs[0];
                }
                return statementMethod.invoke(answer, statementArgs);
              });
    };

  }

  private JdbcTaskDeliveryStore storeHolding(
      final String database,
      final int openRecords) {

    final var store = new JdbcTaskDeliveryStore(
        watched(database), JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME);
    store.createSchemaIfNotExists();
    for (var record = 0; record < openRecords; record++) {
      store
          .record(
              new TaskDelivery(
                  "delivery-%d".formatted(record), "c7", MODULE, PROCESS, "aggregate-%d".formatted(
                      record), "aTask", null, WorkflowTaskOutcome.Kind.COMPLETION_PENDING.name(), null, null, Instant
                          .now(), null));
    }
    statements.clear();
    return store;

  }

  private PreparedStatementUse theOnly(
      final String containedInSql) {

    final var matching = statements
        .stream()
        .filter(statement -> statement.sql().contains(containedInSql))
        .toList();
    assertEquals(
        1,
        matching.size(),
        () -> "one statement containing '%s', but was %s".formatted(containedInSql, statements));
    return matching.getFirst();

  }

  @Test
  @DisplayName("Asking whether an open record exists fetches ONE row, however many the table holds")
  public void theExistenceQuestionFetchesOneRow() {

    final var store = storeHolding("startup-cost-existence", OPEN_RECORDS);

    assertEquals(Boolean.TRUE, store.hasOpenRecords(MODULE, PROCESS));

    // without the limit a driver is free to read the whole result set before the first
    // row is looked at, and the PostgreSQL one does exactly that
    assertEquals(
        1,
        theOnly("SELECT DELIVERY_KEY").maxRows(),
        "the question is whether ANY record is open, so one row is all it may transfer");

  }

  @Test
  @DisplayName("Asking which adapter ids are open lets the database do the reducing")
  public void theAdapterIdQuestionIsAnswered() {

    final var store = storeHolding("startup-cost-adapter-ids", OPEN_RECORDS);

    assertEquals(java.util.Set.of("c7"), store.adapterIdsOfOpenTasks(MODULE, PROCESS));

    // DISTINCT is what bounds this one: the answer has as many rows as the application
    // has adapter ids, which is a handful, and never as many as it has records
    assertTrue(
        theOnly("SELECT DISTINCT ADAPTER_ID").sql().contains("DISTINCT"),
        "the database reduces the records to the ids, not the application");

  }

  /**
   * How many records the core writes while an adapter reports the given number of
   * identifiers its BPMS already held.
   */
  private static int messagesWhileReporting(
      final int heldIdentifiers) {

    final var found = java.util.stream.IntStream
        .range(0, heldIdentifiers)
        .mapToObj(
            number -> new IdentifierHeldElsewhere(
                ScopedIdentifierKind.BPMN_PROCESS_ID, "Process%d"
                    .formatted(number), null, "a definition deployed earlier", true))
        .toList();

    return whileRecording(() -> new NameClashAvoidanceService(null).reportIdentifiersTheBpmsAlreadyHolds(
        "c7",
        MODULE,
        found));

  }

  /**
   * How many records the core writes while the models of two workflow modules declare the
   * given number of identifiers, all of them colliding.
   */
  private static int messagesWhileTwoModulesDeclare(
      final int identifiers) {

    final var declared = java.util.stream.IntStream
        .range(0, identifiers)
        .mapToObj(
            number -> new ModelIdentifier(ScopedIdentifierKind.MESSAGE_NAME, "Message%d".formatted(number), null))
        .toList();
    final var adapter = io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties.ofType("camunda7");
    adapter.setNameClashAvoidance(io.vanillabp.integration.adapter.spi.NameClashAvoidance.NONE);
    final var properties = io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties
        .builder()
        .adapters(java.util.Map.of("c7", adapter))
        .prioritizedAdapters(List.of("c7"))
        .build();
    properties.validateAndLink();
    final var scoping = new NameClashAvoidanceService(properties);

    return whileRecording(
        () -> {
          scoping.reportIdentifiersTheModelsDeclare("c7", MODULE, declared);
          scoping.reportIdentifiersTheModelsDeclare("c7", "another-module", declared);
        });

  }

  @Test
  @DisplayName("The identifiers two workflow modules share are one message per workflow module")
  public void collidingIdentifiersCostOneMessagePerWorkflowModule() {

    final var aSmallApplication = messagesWhileTwoModulesDeclare(1);
    final var aBigOne = messagesWhileTwoModulesDeclare(200);

    assertEquals(1, aSmallApplication, "the module which collides with one deployed before it says so once");
    assertEquals(
        aSmallApplication,
        aBigOne,
        "a workflow module is worth one message, whatever its models declare");

  }

  @Test
  @DisplayName("A held version's identifiers are one message per version, not per workflow")
  public void theIdentifiersOfHeldVersionsCostOneMessagePerVersion() {

    final var adapter = io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties.ofType("camunda7");
    adapter.setNameClashAvoidance(io.vanillabp.integration.adapter.spi.NameClashAvoidance.NONE);
    final var properties = io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties
        .builder()
        .adapters(java.util.Map.of("c7", adapter))
        .prioritizedAdapters(List.of("c7"))
        .build();
    properties.validateAndLink();
    final var scoping = new NameClashAvoidanceService(properties);
    final var declared = List.of(new ModelIdentifier(ScopedIdentifierKind.MESSAGE_NAME, "PaymentReceived", null));
    scoping.reportIdentifiersTheModelsDeclare("c7", MODULE, declared);

    // the same finding on a version two workflows run on and on one which carries a
    // hundred thousand: what the message costs is the version, and the count travels as a
    // number the check around it already asked for
    final var aQuietVersion = whileRecording(
        () -> scoping.reportIdentifiersOfHeldVersion("c7", "another-module", PROCESS, "2", 2L, declared));
    final var aBusyVersion = whileRecording(
        () -> scoping.reportIdentifiersOfHeldVersion("c7", "another-module", PROCESS, "3", 100_000L, declared));

    assertEquals(1, aQuietVersion);
    assertEquals(aQuietVersion, aBusyVersion, "a version is worth one message, whatever runs on it");

  }

  /**
   * How many records the given reporting wrote.
   */
  private static int whileRecording(
      final Runnable reporting) {

    final var root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    final var recorded = new ListAppender<ILoggingEvent>();
    recorded.start();
    root.addAppender(recorded);
    try {
      reporting.run();
    } finally {
      root.detachAppender(recorded);
    }
    return recorded.list.size();

  }

  @Test
  @DisplayName("What the BPMS already holds is one message per workflow module, however much it holds")
  public void theIdentifiersTheBpmsHoldsCostOneMessagePerWorkflowModule() {

    final var onAFreshBpms = messagesWhileReporting(1);
    final var afterYearsOfDeployments = messagesWhileReporting(500);

    assertEquals(1, onAFreshBpms, "a finding is worth one message, not one per line");
    assertEquals(
        onAFreshBpms,
        afterYearsOfDeployments,
        "a start reports this once per workflow module and adapter, whatever the BPMS has collected");

  }

  @Test
  @DisplayName("Both questions cost the same number of statements whatever the table holds")
  public void theNumberOfStatementsDoesNotDependOnTheData() {

    final var almostEmpty = storeHolding("startup-cost-small", 1);
    almostEmpty.hasOpenRecords(MODULE, PROCESS);
    almostEmpty.adapterIdsOfOpenTasks(MODULE, PROCESS);
    final var onASmallTable = statements.size();

    statements.clear();
    final var wellUsed = storeHolding("startup-cost-large", 500);
    wellUsed.hasOpenRecords(MODULE, PROCESS);
    wellUsed.adapterIdsOfOpenTasks(MODULE, PROCESS);

    assertEquals(
        onASmallTable,
        statements.size(),
        () -> "a start asks the same questions however long the application has been running, but was "
            + statements);

  }

}
