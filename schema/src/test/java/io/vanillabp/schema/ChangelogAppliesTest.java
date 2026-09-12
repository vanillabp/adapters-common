package io.vanillabp.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * The changelog applied by Liquibase itself, on H2 - one of the two databases this module
 * promises. What is asserted is not "it ran" but that the tables VanillaBP writes into exist with
 * the columns the runtime would have created, because an application may start with the runtime's
 * tables and switch to this changelog later.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ChangelogAppliesTest {

  private static final String CHANGELOG = "vanillabp/schema/changelog.xml";

  private static Connection h2(
      final String name) throws Exception {

    return DriverManager.getConnection("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(name), "sa", "");

  }

  /**
   * Applies the changelog to a fresh in-memory database and returns a connection to it. Liquibase
   * closes the connection it was handed, so the assertions get one of their own - the database
   * survives thanks to <code>DB_CLOSE_DELAY=-1</code>.
   */
  private static Connection applyTo(
      final String name,
      final Map<String, String> changelogProperties) throws Exception {

    try (var connection = h2(name)) {
      update(connection, changelogProperties);
    }
    return h2(name);

  }

  private static void update(
      final Connection connection,
      final Map<String, String> changelogProperties) throws Exception {

    final var database = DatabaseFactory
        .getInstance()
        .findCorrectDatabaseImplementation(new JdbcConnection(connection));
    try (var liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
      changelogProperties.forEach((
          key,
          value) -> liquibase.setChangeLogParameter(key, value));
      liquibase.update(new Contexts(), new LabelExpression());
    }

  }

  private static Map<String, String> columnsOf(
      final Connection connection,
      final String tableName) throws Exception {

    final var columns = new LinkedHashMap<String, String>();
    try (var resultSet = connection.getMetaData().getColumns(null, null, tableName, null)) {
      while (resultSet.next()) {
        columns.put(resultSet.getString("COLUMN_NAME"), resultSet.getString("TYPE_NAME"));
      }
    }
    return columns;

  }

  @Test
  @DisplayName("The changelog creates both tables with the columns of the runtime's DDL")
  public void bothTablesAreCreated() throws Exception {

    try (var connection = applyTo("changelog", Map.of())) {
      final var outbox = columnsOf(connection, "VANILLABP_PHASE_TWO_OUTBOX");
      assertEquals(
          java.util.List
              .of(
                  "ID", "WORKFLOW_MODULE_ID", "BPMN_PROCESS_ID", "OPERATION", "AGGREGATE_ID",
                  "ADAPTER_ID", "ARGS", "IDEMPOTENCY_KEY", "DEDUP_KEY", "STATUS", "CREATED_AT", "ATTEMPTS",
                  "NEXT_ATTEMPT_AT", "DONE_AT"),
          java.util.List.copyOf(outbox.keySet()));

      final var delivery = columnsOf(connection, "VANILLABP_TASK_DELIVERY");
      assertEquals(
          java.util.List
              .of(
                  "DELIVERY_KEY", "ADAPTER_ID", "WORKFLOW_MODULE_ID", "BPMN_PROCESS_ID",
                  "AGGREGATE_ID", "TASK_DEFINITION", "OUTCOME", "BPMN_ERROR_CODE", "BPMN_ERROR_NAME",
                  "RECORDED_AT", "LAST_SEEN_AT", "TASK_ID", "TASK_CLOSED_AT"),
          java.util.List.copyOf(delivery.keySet()));

    }

  }

  /**
   * The indexes of a table, each with the columns it spans in their order.
   */
  private static Map<String, java.util.List<String>> indexesOf(
      final Connection connection,
      final String tableName) throws Exception {

    final var indexes = new LinkedHashMap<String, java.util.List<String>>();
    try (var resultSet = connection.getMetaData().getIndexInfo(null, null, tableName, false, true)) {
      while (resultSet.next()) {
        final var name = resultSet.getString("INDEX_NAME");
        if (name == null) {
          continue;
        }
        indexes
            .computeIfAbsent(name.toUpperCase(), index -> new java.util.ArrayList<>())
            .add(resultSet.getString("COLUMN_NAME"));
      }
    }
    return indexes;

  }

  @Test
  @DisplayName("The changelog creates the indexes the stores read by, the way the runtime does")
  public void theIndexesOfBothTablesAreCreated() throws Exception {

    try (var connection = applyTo("changelog-indexes", Map.of())) {
      final var outbox = indexesOf(connection, "VANILLABP_PHASE_TWO_OUTBOX");
      assertEquals(
          java.util.List.of("STATUS", "NEXT_ATTEMPT_AT"),
          outbox.get("VANILLABP_PHASE_TWO_OUTBOX_DUE"),
          "the poller asks when its next entry is due on every wake-up: "
              + outbox);
      assertEquals(
          java.util.List.of("STATUS", "DONE_AT"),
          outbox.get("VANILLABP_PHASE_TWO_OUTBOX_AGE"),
          "the retention deletes by the moment an entry was dispatched: "
              + outbox);

      final var delivery = indexesOf(connection, "VANILLABP_TASK_DELIVERY");
      assertEquals(
          java.util.List.of("TASK_ID"),
          delivery.get("VANILLABP_TASK_DELIVERY_TASK"),
          "a task operation reads the record of the task it names: "
              + delivery);
      assertEquals(
          java.util.List.of("LAST_SEEN_AT"),
          delivery.get("VANILLABP_TASK_DELIVERY_AGE"),
          "the retention deletes by the moment a delivery was last seen: "
              + delivery);
    }

  }

  @Test
  @DisplayName("A duplicate dedup key is refused - that is what makes a duplicate schedule a no-op")
  public void theDedupKeyIsUnique() throws Exception {

    try (var connection = applyTo("unique", Map.of())) {
      try (var statement = connection.createStatement()) {
        statement
            .executeUpdate(
                """
                    INSERT INTO VANILLABP_PHASE_TWO_OUTBOX \
                    (ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, IDEMPOTENCY_KEY, DEDUP_KEY, \
                    STATUS, CREATED_AT, ATTEMPTS, NEXT_ATTEMPT_AT) VALUES \
                    ('1', 'module', 'Process', 'START', 'key-1', 'key-1', 'OPEN', CURRENT_TIMESTAMP, 0, \
                    CURRENT_TIMESTAMP)""");
      }

      final var duplicate = org.junit.jupiter.api.Assertions
          .assertThrows(
              java.sql.SQLException.class,
              () -> {
                try (var statement = connection.createStatement()) {
                  statement
                      .executeUpdate(
                          """
                              INSERT INTO VANILLABP_PHASE_TWO_OUTBOX \
                              (ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, IDEMPOTENCY_KEY, \
                              DEDUP_KEY, STATUS, CREATED_AT, ATTEMPTS, NEXT_ATTEMPT_AT) VALUES \
                              ('2', 'module', 'Process', 'START', 'key-1', 'key-1', 'OPEN', \
                              CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP)""");
                }
              });
      assertTrue(duplicate.getMessage().toLowerCase().contains("unique"), duplicate.getMessage());
    }

  }

  @Test
  @DisplayName("A table name of the application is a changelog property, so a renamed table needs no fork")
  public void tableNamesAreProperties() throws Exception {

    try (var connection = applyTo(
        "renamed",
        Map.of("vanillabp.outbox.table", "MY_OUTBOX", "vanillabp.delivery.table", "MY_DELIVERIES"))) {
      assertTrue(columnsOf(connection, "MY_OUTBOX").containsKey("DEDUP_KEY"));
      assertTrue(columnsOf(connection, "MY_DELIVERIES").containsKey("DELIVERY_KEY"));
      assertTrue(columnsOf(connection, "VANILLABP_PHASE_TWO_OUTBOX").isEmpty());
    }

  }

}
