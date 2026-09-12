package io.vanillabp.migration.test.jdbc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.jdbc.JdbcSchema;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Whether an index a later version of VanillaBP reads by is there, which is what decides between a
 * startup saying nothing and a startup naming the statement which adds it. The question is asked of
 * the JDBC metadata, where the spelling of an unquoted identifier depends on the database, so it is
 * worth a test of its own.
 */
@ExtendWith(SuppressOutputExtension.class)
public class IndexIsThereOrNotTest {

  private static final String TABLE = "A_TABLE";

  @Test
  @DisplayName("An index which is there is found, whatever case the question uses")
  public void anIndexWhichIsThereIsFound() throws Exception {

    try (var connection = DriverManager.getConnection("jdbc:h2:mem:index-is-there;DB_CLOSE_DELAY=-1")) {
      try (var statement = connection.createStatement()) {
        statement.executeUpdate("CREATE TABLE A_TABLE (STATUS VARCHAR(16), DONE_AT TIMESTAMP)");
        statement.executeUpdate("CREATE INDEX A_TABLE_AGE ON A_TABLE (STATUS, DONE_AT)");
      }

      assertTrue(JdbcSchema.indexExists(connection, TABLE, "A_TABLE_AGE"));
      assertTrue(
          JdbcSchema.indexExists(connection, TABLE, "a_table_age"),
          "a database which folds identifiers must not turn this into a missing index");
    }

  }

  @Test
  @DisplayName("An index which is not there is missing, and so is one on a table which is not there")
  public void anIndexWhichIsNotThereIsMissing() throws Exception {

    try (var connection = DriverManager.getConnection("jdbc:h2:mem:index-is-not-there;DB_CLOSE_DELAY=-1")) {
      try (var statement = connection.createStatement()) {
        statement.executeUpdate("CREATE TABLE A_TABLE (STATUS VARCHAR(16), DONE_AT TIMESTAMP)");
      }

      assertFalse(JdbcSchema.indexExists(connection, TABLE, "A_TABLE_AGE"));
      assertFalse(JdbcSchema.indexExists(connection, "ANOTHER_TABLE", "A_TABLE_AGE"));
    }

  }

}
