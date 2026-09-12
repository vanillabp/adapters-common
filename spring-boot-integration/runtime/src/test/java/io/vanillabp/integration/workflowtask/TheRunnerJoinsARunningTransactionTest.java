package io.vanillabp.integration.workflowtask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which transaction the three forms of {@link TransactionRunner} really run in on Spring
 * Boot, measured on a database rather than on the propagation constant.
 * <p>
 * The form the outbox dispatch asks for is the one worth a test of its own. An outbox
 * which dispatches inside a transaction of its own - gruelbox on this platform - ticks the
 * entry off in that transaction, so a dispatch which throws afterwards is repeated. What
 * the application wrote while VanillaBP called it has to go back with it, and that only
 * holds if the runner joined instead of opening a second transaction.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TheRunnerJoinsARunningTransactionTest {

  private EmbeddedDatabase database;

  private JdbcTemplate jdbc;

  private TransactionTemplate callersTransaction;

  private TransactionRunner runner;

  @BeforeEach
  public void buildADatabaseAndARunner() {

    database = new EmbeddedDatabaseBuilder()
        .setType(EmbeddedDatabaseType.H2)
        .generateUniqueName(true)
        .build();
    final var transactionManager = new DataSourceTransactionManager((DataSource) database);
    jdbc = new JdbcTemplate(database);
    jdbc.execute("create table WHAT_THE_CALL_WROTE (note varchar(64))");
    callersTransaction = new TransactionTemplate(transactionManager);
    runner = new SpringTransactionRunner(transactionManager);

  }

  @AfterEach
  public void dropTheDatabase() {

    database.shutdown();

  }

  private void write(
      final String note) {

    jdbc.update("insert into WHAT_THE_CALL_WROTE (note) values (?)", note);

  }

  private int rowsWritten() {

    return jdbc.queryForObject("select count(*) from WHAT_THE_CALL_WROTE", Integer.class);

  }

  @Test
  @DisplayName("requireTransaction joins the caller's transaction, so its rollback takes the write")
  public void theRequiredFormIsRolledBackWithTheCaller() {

    assertThrows(
        IllegalStateException.class,
        () -> callersTransaction
            .execute(status -> {
              runner
                  .requireTransaction(() -> {
                    write("written while the caller's transaction was open");
                    return null;
                  });
              throw new IllegalStateException("the dispatch fails behind the call");
            }));

    assertEquals(0, rowsWritten(), "the write survived the rollback of the transaction it rode");

  }

  @Test
  @DisplayName("requireTransaction opens one where the caller has none")
  public void theRequiredFormOpensOneOfItsOwn() {

    runner
        .requireTransaction(() -> {
          write("written without a caller's transaction");
          return null;
        });

    assertEquals(1, rowsWritten());

  }

  @Test
  @DisplayName("requireNew commits for itself, whatever the caller's transaction does")
  public void theNewFormSurvivesTheCallersRollback() {

    // the counterpart of the first test, and the reason the two forms are told apart:
    // a handler VanillaBP runs in a transaction of its own keeps what it wrote even
    // where everything around it is rolled back
    assertThrows(
        IllegalStateException.class,
        () -> callersTransaction
            .execute(status -> {
              runner
                  .requireNew(() -> {
                    write("written in a transaction of its own");
                    return null;
                  });
              throw new IllegalStateException("the caller fails behind the call");
            }));

    assertEquals(1, rowsWritten());

  }

  @Test
  @DisplayName("inCurrent refuses to run where no transaction is open")
  public void theCurrentFormNeedsOne() {

    assertThrows(
        IllegalTransactionStateException.class,
        () -> runner
            .inCurrent(() -> {
              write("never written");
              return null;
            }));

    assertEquals(0, rowsWritten());

  }

}
