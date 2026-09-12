package io.vanillabp.integration.outbox.gruelbox;

import com.gruelbox.transactionoutbox.Submitter;
import com.gruelbox.transactionoutbox.TransactionOutboxEntry;

import lombok.extern.slf4j.Slf4j;

/**
 * Bridges gruelbox's per-entry state to the dispatch bean: gruelbox invokes the
 * scheduled method with the persisted arguments only - the
 * {@link TransactionOutboxEntry} (and with it the attempts count) is not visible
 * at the invocation. This {@link Submitter} wrapper is the one gruelbox hook
 * carrying the entry BEFORE its invocation: it records &quot;this entry was
 * dispatched before&quot; in a {@link ThreadLocal} read by
 * {@link GruelboxPhaseTwoOutboxAutoConfiguration}'s dispatch bean on the same
 * thread, feeding the START re-dispatch mitigation of the core.
 * <p>
 * Known limitation (accepted): gruelbox increments the attempts count only when
 * an attempt FAILS - after a hard crash between a successful BPMS call and
 * committing the processed flag, the recovered entry still carries
 * <code>attempts == 0</code> and is re-dispatched without the mitigation probe
 * (the documented at-least-once residual). Failure-retries - including the
 * classic &quot;BPMS call succeeded but recording the completion failed&quot; -
 * are detected, also across restarts (the count is persisted).
 * <p>
 * <strong>It is also the gate holding entries back until VanillaBP dispatches
 * them.</strong> Gruelbox submits an entry as soon as the scheduling transaction
 * commits, while Spring Boot answers requests before VanillaBP deployed its
 * models. A workflow started in that window would be carried to a BPMS which does
 * not hold its process yet, and the BPMS saying so is a failure no repetition can
 * fix. So nothing is submitted while the gate is closed, which gruelbox treats
 * like an executor refusing the work: the entry stays committed and due, and the
 * first {@link com.gruelbox.transactionoutbox.TransactionOutbox#flush()} carries
 * it. Such an entry waits for the moment the dispatcher starts polling, which is
 * once per application start, and it reaches the dispatch bean as a repetition,
 * because gruelbox stamps the attempt time of every entry a flush picks up.
 * <p>
 * Nothing closes the gate unless something can open it again. Building a
 * {@link GruelboxPhaseTwoOutboxDispatcher} for this submitter closes it, and that
 * dispatcher opens it for good when it starts polling. An outbox built without a
 * dispatcher (a test, or an application flushing the outbox itself) keeps
 * dispatching right after the commit, as it did before this gate existed: a gate
 * nobody opens would hold every entry of such an application forever. See
 * {@code GruelboxHoldsEntriesBackUntilDispatchingStartedTest}.
 */
@Slf4j
public final class GruelboxRedispatchAwareSubmitter implements Submitter {

  private static final ThreadLocal<Boolean> PREVIOUSLY_ATTEMPTED = ThreadLocal.withInitial(() -> Boolean.FALSE);

  private final Submitter delegate;

  /**
   * Whether entries are kept for the first flush instead of being submitted. Read on
   * the thread which committed the scheduling transaction and written on the thread
   * starting the dispatcher, so it is volatile.
   */
  private volatile boolean holdingBack;

  public GruelboxRedispatchAwareSubmitter(
      final Submitter delegate) {

    this.delegate = delegate;

  }

  /**
   * @return Whether the entry dispatched on the current thread was attempted
   *         before (a retried entry)
   */
  public static boolean isPreviouslyAttempted() {

    return PREVIOUSLY_ATTEMPTED.get();

  }

  /**
   * Closes the gate. Called by the {@link GruelboxPhaseTwoOutboxDispatcher} built for
   * this submitter, which is the one thing able to open it again.
   */
  void holdBackUntilDispatchingStarted() {

    holdingBack = true;

  }

  /**
   * Opens the gate, for the rest of this application's life. Shutdown does not close
   * it again: an entry which cannot be submitted while the application goes down is
   * dispatched by the next instance anyway, and a gate closed on the way out would
   * only add a second reason for an entry not to move.
   */
  void dispatchingStarted() {

    holdingBack = false;

  }

  @Override
  public void submit(
      final TransactionOutboxEntry entry,
      final java.util.function.Consumer<TransactionOutboxEntry> localExecutor) {

    if (holdingBack) {
      log.debug(
          "Keeping {} for the first poll: VanillaBP has not started dispatching yet",
          entry.description());
      return;
    }

    delegate.submit(
        entry,
        entryOnWorkerThread -> {
          PREVIOUSLY_ATTEMPTED.set(
              (entryOnWorkerThread.getAttempts() > 0) || (entryOnWorkerThread.getLastAttemptTime() != null));
          try {
            localExecutor.accept(entryOnWorkerThread);
          } finally {
            PREVIOUSLY_ATTEMPTED.remove();
          }
        });

  }

}
