package io.vanillabp.integration.outbox.gruelbox;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.function.Supplier;

import com.gruelbox.transactionoutbox.Persistor;
import com.gruelbox.transactionoutbox.TransactionManager;
import com.gruelbox.transactionoutbox.TransactionOutboxEntry;
import com.gruelbox.transactionoutbox.TransactionOutboxListener;

import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.spi.PhaseTwoPermanentFailure;
import lombok.extern.slf4j.Slf4j;

/**
 * Makes the gruelbox store keep the two promises the stores VanillaBP writes itself keep:
 * an entry whose failure the adapter called permanent is blocked after the first attempt,
 * and the ERROR which reports a blocked entry says which workflow was lost.
 * <p>
 * Gruelbox counts attempts and blocks an entry once
 * <code>vanillabp.outbox.block-after-attempts</code> of them are used up, and it knows
 * nothing about VanillaBP's classification. Repeating a failure the BPMS answers the same
 * way every time is a long row of attempts nobody learns anything from, so this listener
 * writes the block itself: gruelbox calls it AFTER the failed attempt was committed, and the
 * entry it hands over carries the version that commit wrote, which is what
 * {@link Persistor#update(com.gruelbox.transactionoutbox.Transaction, TransactionOutboxEntry)}
 * needs to write the blocked flag on top of it.
 * <p>
 * The entry which arrives here already blocked is the one gruelbox gave up on itself.
 * That case is only reported, never blocked again, so an entry ends in exactly one ERROR
 * of VanillaBP's however it got there. Gruelbox writes a line of its own next to it,
 * which names the entry id and not the workflow.
 * <p>
 * An entry which is not a phase-two dispatch is left alone. The outbox bean belongs to
 * VanillaBP, but an application may schedule work of its own on it, and blocking
 * somebody else's entry is not this listener's business.
 */
@Slf4j
public class GruelboxPhaseTwoFailureListener implements TransactionOutboxListener {

  /**
   * The method of a phase-two dispatch, which is how an entry of VanillaBP's is
   * recognised among whatever else the outbox carries.
   */
  private static final Method PHASE_TWO_DISPATCH = phaseTwoDispatchMethod();

  /**
   * The name this store reports itself under, the same one its gauge of waiting entries
   * uses.
   */
  private static final String STORE = GruelboxPhaseTwoOutbox.class.getSimpleName();

  private final Persistor persistor;

  private final TransactionManager transactionManager;

  /**
   * Read per blocked entry rather than injected once: the outbox is built while the
   * application context is still coming up, and the metrics bean may not exist yet - or
   * at all, because Micrometer is optional.
   */
  private final Supplier<VanillaBpMetrics> metrics;

  /**
   * @param persistor The persistor of the outbox this listener belongs to, used to write
   *          the blocked flag
   * @param transactionManager The transaction manager of that outbox, giving the
   *          transaction the write runs in
   * @param metrics What a blocked entry is counted into
   */
  public GruelboxPhaseTwoFailureListener(
      final Persistor persistor,
      final TransactionManager transactionManager,
      final Supplier<VanillaBpMetrics> metrics) {

    this.persistor = persistor;
    this.transactionManager = transactionManager;
    this.metrics = metrics;

  }

  @Override
  public void failure(
      final TransactionOutboxEntry entry,
      final Throwable cause) {

    if (!isPhaseTwoDispatch(entry)) {
      return;
    }

    final var permanent = PhaseTwoPermanentFailure.isPermanent(cause);

    if (entry.isBlocked()) {
      // gruelbox used up 'vanillabp.outbox.block-after-attempts' and wrote the block
      // before it called here - nothing left to do but say which workflow it was
      reportAttemptsUsedUp(entry, cause, permanent);
      return;
    }

    if (!permanent) {
      return;
    }

    if (blockNow(entry)) {
      reportPermanentFailure(entry, cause);
    }

  }

  /**
   * Writes the blocked flag of an entry the adapter said repeating cannot fix.
   *
   * @param entry The entry to block
   * @return Whether the entry was blocked. A write which does not find the entry at the
   *         version it was handed over at lost against somebody else touching the same
   *         row, and that other writer decides what the entry is
   */
  private boolean blockNow(
      final TransactionOutboxEntry entry) {

    try {
      entry.setBlocked(true);
      transactionManager.inTransactionThrows(transaction -> persistor.update(transaction, entry));
      return true;
    } catch (final Exception e) {
      entry.setBlocked(false);
      log.warn(
          "Could not block the outbox entry '{}' although the adapter said that repeating cannot "
              + "fix its failure - it is attempted again until 'vanillabp.outbox.block-after-attempts' "
              + "are used up",
          entry.getId(),
          e);
      return false;
    }

  }

  /**
   * Says which workflow was lost when this listener blocked the entry, in the words the
   * stores VanillaBP writes itself use.
   *
   * @param entry The blocked entry
   * @param cause What the dispatch failed with
   */
  private void reportPermanentFailure(
      final TransactionOutboxEntry entry,
      final Throwable cause) {

    final var call = argumentsOf(entry);
    count(call, true);
    log.error(
        "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
            + "on adapter '{}' failed for a reason repeating cannot fix - the outbox entry '{}' is "
            + "blocked and has to be cleaned up manually!",
        call[0],
        call[2],
        call[1],
        call[3],
        adapterOf(call),
        entry.getId(),
        cause);

  }

  /**
   * Says which workflow was lost when gruelbox gave up on the entry itself.
   *
   * @param entry The blocked entry
   * @param cause What the last attempt failed with
   * @param permanent Whether that last failure was one the adapter calls permanent
   */
  private void reportAttemptsUsedUp(
      final TransactionOutboxEntry entry,
      final Throwable cause,
      final boolean permanent) {

    final var call = argumentsOf(entry);
    count(call, permanent);
    log.error(
        "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
            + "on adapter '{}' failed {} times - the outbox entry '{}' is now blocked and has to be "
            + "cleaned up manually!",
        call[0],
        call[2],
        call[1],
        call[3],
        adapterOf(call),
        entry.getAttempts(),
        entry.getId(),
        cause);

  }

  /**
   * @param call The arguments of the blocked dispatch
   * @param permanent Whether the adapter said that repeating cannot help
   */
  private void count(
      final Object[] call,
      final boolean permanent) {

    metrics
        .get()
        .outboxEntryBlocked(STORE, String.valueOf(call[0]), permanent);

  }

  /**
   * @param call The arguments of the blocked dispatch
   * @return The adapter the operation was meant for. An operation which was scheduled
   *         before any adapter was elected carries none, and "none" reads better in a
   *         log line than the word null does
   */
  private static String adapterOf(
      final Object[] call) {

    return (call[4] == null)
        ? "none"
        : String.valueOf(call[4]);

  }

  /**
   * @param entry The entry a dispatch failed for
   * @return The arguments the dispatch was scheduled with, in the order
   *         {@link GruelboxPhaseTwoDispatch#dispatch} declares them
   */
  private static Object[] argumentsOf(
      final TransactionOutboxEntry entry) {

    return entry
        .getInvocation()
        .getArgs();

  }

  /**
   * @param entry The entry a dispatch failed for
   * @return Whether the entry carries a phase-two dispatch of VanillaBP's
   */
  private static boolean isPhaseTwoDispatch(
      final TransactionOutboxEntry entry) {

    final var invocation = entry.getInvocation();
    return (invocation != null) && PHASE_TWO_DISPATCH.getName().equals(invocation.getMethodName()) && Arrays
        .equals(PHASE_TWO_DISPATCH.getParameterTypes(), invocation.getParameterTypes());

  }

  private static Method phaseTwoDispatchMethod() {

    try {
      return GruelboxPhaseTwoDispatch.class.getMethod(
          "dispatch",
          String.class,
          String.class,
          String.class,
          String.class,
          String.class,
          String.class);
    } catch (final NoSuchMethodException e) {
      throw new IllegalStateException(
          "The phase-two dispatch of the gruelbox outbox changed its signature, so a failed entry "
              + "can no longer be recognised as VanillaBP's!", e);
    }

  }

}
