package io.vanillabp.integration.outbox.gruelbox;

import java.util.OptionalLong;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DataSourceUtils;

import com.gruelbox.transactionoutbox.AlreadyScheduledException;
import com.gruelbox.transactionoutbox.TransactionOutbox;

import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import lombok.extern.slf4j.Slf4j;

/**
 * The default {@link PhaseTwoOutbox} implementation for Spring Boot applications using
 * JPA: delegates to a <a href="https://github.com/gruelbox/transaction-outbox">gruelbox
 * transaction-outbox</a> configured with Spring's transaction manager, so the outbox
 * entry is enlisted in the currently running local (JDBC) transaction.
 * <p>
 * The idempotency contract of {@link PhaseTwoOutbox} maps onto gruelbox's
 * <code>uniqueRequestId</code> mechanism: the {@link PhaseTwoCall#idempotencyKey()} is
 * used as unique request ID, enforced by a unique constraint of gruelbox's outbox
 * table. A duplicate schedule raises {@link AlreadyScheduledException} which is turned
 * into the contract's no-op (<code>false</code>). Successfully dispatched entries with
 * a unique request ID are retained by gruelbox until the configured retention
 * threshold passes (the contract's "DONE instead of delete").
 * <p>
 * <strong>Deduplication has to span the entries still waiting for their dispatch
 * only</strong>, and gruelbox' unique constraint spans its retained entries as well.
 * Its table has no column this store could move a dispatched key into, so the release
 * happens when it is needed: before scheduling, the store reads the row of that unique
 * request ID and looks at gruelbox' <code>processed</code> flag. A processed row is
 * DELETED - it has done its work, and its trail ends there, which is the price of
 * gruelbox owning the table - and the new operation is then scheduled. A row which is
 * not processed yet means an identical operation is still planned, and the schedule is
 * discarded. Both happen in the caller's transaction, on the connection
 * {@code DataSourceUtils} binds to it, so a rollback takes the release with it.
 * <p>
 * The at-least-once guarantee is not weakened by that: a redispatch reads the very row
 * which is not processed yet, so gruelbox' own attempt bookkeeping carries it - never
 * the unique request ID. Which is also why the key VanillaBP derives is bounded to
 * {@link PhaseTwoCall#MAX_IDEMPOTENCY_KEY_LENGTH} characters: gruelbox refuses a longer
 * unique request ID before any database sees it.
 * <p>
 * A store built with the constructor which takes no data source cannot read that flag.
 * It falls back to gruelbox' own answer, which deduplicates against retained entries as
 * well - kept for tests, and named here so nobody mistakes it for the contract.
 * <p>
 * {@link PhaseTwoOutbox#adapterIdsOfPendingCalls(String, String)} is the one question of
 * the contract this store leaves unanswered, so the startup check which names the adapter
 * ids still waiting is skipped wherever this store is the one in use. gruelbox keeps a
 * call as a serialized invocation rather than in columns, so there is no adapter id to
 * ask about without reading and deserializing the whole table, which is the cost
 * decision 19 in the repository's DECISIONS.md rules out for a start. The stores
 * VanillaBP owns its tables in answer it.
 */
@Slf4j
public class GruelboxPhaseTwoOutbox implements PhaseTwoOutbox {

  private final TransactionOutbox transactionOutbox;

  /**
   * Where gruelbox' table lives, needed to count the entries waiting for their
   * dispatch. <code>null</code> where the caller did not supply one - the pending
   * meter is then absent rather than wrong.
   */
  private final DataSource dataSource;

  /**
   * The table gruelbox stores its entries in.
   */
  private final String tableName;

  /**
   * Creates an outbox which cannot count its pending entries - kept for tests.
   *
   * @param transactionOutbox The gruelbox transaction outbox
   */
  public GruelboxPhaseTwoOutbox(
      final TransactionOutbox transactionOutbox) {

    this(transactionOutbox, null, null);

  }

  /**
   * @param transactionOutbox The gruelbox transaction outbox
   * @param dataSource Where gruelbox' table lives
   * @param tableName The table gruelbox stores its entries in
   */
  public GruelboxPhaseTwoOutbox(
      final TransactionOutbox transactionOutbox,
      final DataSource dataSource,
      final String tableName) {

    this.transactionOutbox = transactionOutbox;
    this.dataSource = dataSource;
    this.tableName = tableName;

  }

  /**
   * Counts the entries gruelbox has not processed yet. gruelbox has no API for it, so
   * the count reads its table directly - along the index it creates itself
   * (<code>IX_TXNO_OUTBOX_1</code> over <code>processed, blocked,
   * nextAttemptTime</code>), which is why one column is enough and no dialect-specific
   * literal is needed: the driver knows how to write a boolean into whatever type the
   * column has on this database.
   */
  @Override
  public OptionalLong pendingCalls() {

    if ((dataSource == null) || (tableName == null)) {
      return OptionalLong.empty();
    }
    final var countPending = "SELECT COUNT(*) FROM %s WHERE processed = ?".formatted(tableName);
    try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(countPending)) {
      statement.setBoolean(1, false);
      try (var resultSet = statement.executeQuery()) {
        return resultSet.next()
            ? OptionalLong.of(resultSet.getLong(1))
            : OptionalLong.empty();
      }
    } catch (final java.sql.SQLException e) {
      // a metric must never be the reason an application fails - the gauge reports
      // nothing for this collection and the next one tries again
      log.debug("Could not count the pending entries of gruelbox' outbox table '{}'", tableName, e);
      return OptionalLong.empty();
    }

  }

  /**
   * When gruelbox' next flush has something to do, which is what its dispatcher waits for.
   * <p>
   * Both halves of a flush read the same column, because gruelbox keeps both moments in
   * <code>nextAttemptTime</code>: an entry waiting for its dispatch carries its next attempt
   * there, and an entry which was dispatched carries the moment its retention runs out. What
   * differs is the <code>processed</code> flag, and they are therefore asked as two questions
   * instead of one: the index gruelbox creates for its own flush spans
   * <code>(processed, blocked, nextAttemptTime)</code>, so a question naming both flags is
   * answered from that index, while one naming only <code>blocked</code> would read the whole
   * table - and that cost grows with everything the table ever held.
   * <p>
   * A BLOCKED entry is left out of both, and that is the point of the predicate: it waits for a
   * person rather than for a clock, so a store which holds nothing else has nothing to be woken
   * for.
   *
   * @return The moment of the earliest entry, or <code>null</code> where nothing is owed -
   *         which is the answer a store without a data source gives as well, leaving its
   *         poller on the configured cap
   */
  public java.time.Instant earliestDueAt() {

    if ((dataSource == null) || (tableName == null)) {
      return null;
    }
    final var selectEarliest = "SELECT MIN(nextAttemptTime) FROM %s WHERE processed = ? AND blocked = ?"
        .formatted(tableName);
    try (var connection = dataSource.getConnection()) {
      final var nextAttempt = earliest(connection, selectEarliest, false);
      final var retentionRunsOut = earliest(connection, selectEarliest, true);
      if (nextAttempt == null) {
        return retentionRunsOut;
      }
      if (retentionRunsOut == null) {
        return nextAttempt;
      }
      return nextAttempt.isBefore(retentionRunsOut) ? nextAttempt : retentionRunsOut;
    } catch (final java.sql.SQLException e) {
      // the flush which follows reports the same problem with its own message, and a
      // poller which stops asking is worse than one which asks at the cap
      log.debug("Could not read the next attempt time of gruelbox' outbox table '{}'", tableName, e);
      return null;
    }

  }

  /**
   * The earliest moment one of the two kinds of entry wants something.
   *
   * @param connection The connection to ask on
   * @param query The aggregate over the table, taking the two flags
   * @param processed Whether to look at the entries which were dispatched already
   * @return The moment or <code>null</code> where there is no such entry
   */
  private java.time.Instant earliest(
      final java.sql.Connection connection,
      final String query,
      final boolean processed) throws java.sql.SQLException {

    try (var statement = connection.prepareStatement(query)) {
      statement.setBoolean(1, processed);
      statement.setBoolean(2, false);
      try (var resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        final var earliest = resultSet.getTimestamp(1);
        return earliest == null ? null : earliest.toInstant();
      }
    }

  }

  @Override
  public boolean schedule(
      final PhaseTwoCall call) {

    final var idempotencyKey = call
        .idempotencyKey()
        .orElse(null);
    if ((idempotencyKey != null) && !releaseDispatchedEntry(call, idempotencyKey)) {
      logDiscardedSchedule(call);
      return false;
    }
    try {
      transactionOutbox
          .with()
          .uniqueRequestId(idempotencyKey)
          .schedule(GruelboxPhaseTwoDispatch.class)
          .dispatch(
              call.operation(),
              call.workflowModuleId(),
              call.bpmnProcessId(),
              call.workflowAggregateId(),
              call.adapterId(),
              PhaseTwoCall.serializeArgs(call.args()));
      return true;
    } catch (AlreadyScheduledException e) {
      // two nodes scheduling the same operation at the same moment, or a store which
      // cannot read gruelbox' table (see the class javadoc)
      logDiscardedSchedule(call);
      return false;
    }

  }

  /**
   * Frees the unique request ID of an entry which was dispatched already, so the
   * operation can be planned again.
   *
   * @return Whether the key is free now - <code>false</code> if an entry of that key is
   *         still waiting for its dispatch
   */
  private boolean releaseDispatchedEntry(
      final PhaseTwoCall call,
      final String idempotencyKey) {

    if ((dataSource == null) || (tableName == null)) {
      // no way to look at gruelbox' processed flag: gruelbox answers instead, which
      // deduplicates the retained entries as well
      return true;
    }
    final var selectEntry = "SELECT id, processed FROM %s WHERE uniqueRequestId = ?".formatted(tableName);
    final var connection = DataSourceUtils.getConnection(dataSource);
    try {
      final String entryId;
      try (var statement = connection.prepareStatement(selectEntry)) {
        statement.setString(1, idempotencyKey);
        try (var resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            return true;
          }
          if (!resultSet.getBoolean(2)) {
            return false;
          }
          entryId = resultSet.getString(1);
        }
      }
      final var deleteEntry = "DELETE FROM %s WHERE id = ? AND processed = ?".formatted(tableName);
      try (var statement = connection.prepareStatement(deleteEntry)) {
        statement.setString(1, entryId);
        statement.setBoolean(2, true);
        // 0 rows: the dispatcher's retention cleanup got there first, which frees the
        // key just as well
        statement.executeUpdate();
      }
      log.debug(
          "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' was "
              + "dispatched before - released the entry so this operation can be planned again",
          call.operation(),
          call.bpmnProcessId(),
          call.workflowModuleId(),
          call.workflowAggregateId());
      return true;
    } catch (final java.sql.SQLException e) {
      throw new IllegalStateException(
          """
              Could not look up the phase-two outbox entry of BPMN process '%s' of workflow module \
              '%s' in gruelbox' table '%s'!"""
              .formatted(call.bpmnProcessId(), call.workflowModuleId(), tableName), e);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }

  }

  /**
   * The technical half of a discarded schedule. Which of the two causes it was - a
   * redelivered dispatch or an operation lost against one still waiting - the store
   * cannot tell, so the core reports it to the caller and this line stays at DEBUG.
   */
  private static void logDiscardedSchedule(
      final PhaseTwoCall call) {

    log.debug(
        "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' is still "
            + "waiting for its dispatch - the schedule of an identical operation was discarded",
        call.operation(),
        call.bpmnProcessId(),
        call.workflowModuleId(),
        call.workflowAggregateId());

  }

}
