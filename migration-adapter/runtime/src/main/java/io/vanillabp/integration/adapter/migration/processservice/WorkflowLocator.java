package io.vanillabp.integration.adapter.migration.processservice;

import java.util.List;
import java.util.function.Function;

import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.spi.WorkflowAdapterCache;
import lombok.extern.slf4j.Slf4j;

/**
 * THE BPMS election for operations on EXISTING workflows (complete/cancel task,
 * user task, message correlation - new workflows always start in the
 * first-priority adapter): locates the BPMS holding a workflow (or one of its
 * tasks) by probing the prioritized adapters in order. The probe is pluggable per
 * operation because it is context-specific: locating a service task probes
 * {@code awarenessOfTask}, message correlation probes {@code awarenessOfWorkflow},
 * user tasks their own awareness - the walk/retry/no-fallback semantics here are
 * shared by all of them.
 * <p>
 * An optional {@link WorkflowAdapterCache} short-cuts the walk: a successful
 * election puts the (workflow module, BPMN process, aggregate ID) &rarr; adapter
 * ID association, and so do the moments VanillaBP knows the answer for certain
 * ({@link #remember(Object, String)}: phase two of a start, and every inbound
 * delivery). The end of a workflow is the one delivery which does not refresh the
 * association but MARKS it ({@link #rememberWorkflowEnded(Object, String)}): the hint
 * is still read - an operation arriving after the end stays a warned no-op - and it
 * leaves the cache long before a hint of a running workflow would. The next election for the same workflow probes the cached adapter
 * first. Cache entries are HINTS, not truth - a hit whose adapter answers
 * {@link WorkflowAwareness#UNKNOWN_TO_BPMS} for longer than that adapter's
 * {@code workflowVisibilityDelay} falls through to the full walk and repairs the
 * entry. {@link WorkflowAwareness#BPMS_UNAVAILABLE} on a cached adapter follows
 * the retry-never-fallback contract below (it never falls through - the
 * unavailable BPMS most probably holds the workflow).
 * <p>
 * <b>Which BPMN process id a hint sits under.</b> The one which was running when
 * VanillaBP learned the answer, and that is not always the id the application calls
 * its operations on: a task of a secondary process is delivered to the instance of
 * THAT process, so the hint is written under the secondary id while
 * {@code ProcessService} always addresses the primary one. The write stays where it
 * is - it names the process which was actually running, and nothing has to be
 * migrated - and the READ looks under every id the workflow service serves, this
 * instance's own first ({@link #setBpmnProcessIdsToReadUnder(List)}). All of them
 * belong to one workflow aggregate, so the first hint found is a hint about this
 * workflow (see decision 5 in the repository's DECISIONS.md).
 * <p>
 * A hint is also what turns the meaning of an unknown answer around: an adapter
 * which SHOULD hold the workflow and does not report it is a reason to look again
 * (an eventually consistent BPMS needs a moment after the start), while the same
 * answer without any hint is a workflow nobody ever heard of, which fails
 * immediately.
 * <p>
 * <b>What the walk cannot do.</b> It stops at the first
 * {@link WorkflowAwareness#ACTIVE} and is therefore only as right as the answers it
 * gets. Whether a workflow belongs to an adapter is the ADAPTER's question, not this
 * one's: two adapter ids may address one backend (the migration from one scoping to
 * another), and two workflow modules of one backend may carry the same aggregate ID,
 * so neither a task ID nor an aggregate ID tells the core anything. The election
 * contract in {@code MigratableProcessService} is where that duty is written down,
 * and {@code ElectionScopeContractTest} shows both halves: the walk reaching the
 * holder where the adapters answer for their own scope, and stopping at the wrong one
 * where an adapter claims more than it holds.
 * <p>
 * Walk contract (see {@code MigratableProcessService}):
 * <ul>
 * <li>{@link WorkflowAwareness#ACTIVE} - this adapter executes the operation, stop
 * probing;</li>
 * <li>{@link WorkflowAwareness#UNKNOWN_TO_BPMS} - ask the next adapter;</li>
 * <li>{@link WorkflowAwareness#COMPLETED} - the subject is known but already done:
 * the operation becomes a no-op (reported to the caller);</li>
 * <li>{@link WorkflowAwareness#BPMS_UNAVAILABLE} - NEVER falls back to the next
 * adapter (the unavailable BPMS might hold the subject): the walk fails naming the
 * unavailable adapter, immediately where nothing may sleep.</li>
 * </ul>
 * <p>
 * <b>Who may sleep, and where.</b> Every caller says how patient the walk may be
 * ({@link Patience}), because the same walk runs in places whose cost is not
 * comparable. In phase one it runs INSIDE the caller's database transaction, holding
 * its connection and the locks on the workflow aggregate: a walk which sleeps there
 * drains the connection pool of every caller at once, which is why phase one asks
 * once and never sleeps. At dispatch time no application transaction is open and a
 * repetition costs an entry another attempt rather than a connection, so that is where
 * a BPMS gets a second chance and where a read model gets the moment it needs
 * (decision 27 in the repository's DECISIONS.md) - the moment is taken by giving the
 * entry back with a due time, because the dispatching thread carries the entries of
 * every other workflow too. A read of the viewer/history API is the one caller which
 * really sleeps: there is no outbox entry behind it which could ask again later, so an
 * answer it does not wait for is an error the application sees.
 * <p>
 * The two rules this walk rests on are written down where the adapters can read them too: an
 * adapter answers only for its own scope and the walk never falls back (decision 4 in the
 * repository's DECISIONS.md), and a remembered adapter is a hint which is probed rather than
 * trusted (decision 5 in the repository's DECISIONS.md).
 */
@Slf4j
public final class WorkflowLocator {

  /**
   * How patient a walk may be - see the type javadoc for why this is the caller's
   * decision rather than the locator's.
   */
  public enum Patience {

    /**
     * Ask every adapter once and never sleep: the answer is needed while the
     * caller's transaction is open.
     */
    NONE,

    /**
     * Repeat an unavailable BPMS a few times, but never wait for a read model to
     * catch up. Two callers ask for this, for different reasons. What a task probe
     * answers is exact - a task the BPMS does not know stays unknown however long
     * anybody waits. And the dispatch of a phase-two entry has somebody who asks
     * again: it hands the entry back to the outbox with a short due time, which
     * leaves its thread free for the entries of all the other workflows.
     */
    RETRY_UNAVAILABLE,

    /**
     * Additionally wait out the {@code workflowVisibilityDelay} of an adapter a hint
     * points at: the workflow exists, its BPMS just has not made it findable yet. Only
     * a read of the viewer/history API asks for this, because nobody repeats a read -
     * an answer it does not wait for is an error the application sees.
     */
    WAIT_FOR_VISIBILITY

  }

  /**
   * How often a {@link WorkflowAwareness#BPMS_UNAVAILABLE} probe is retried before
   * the walk fails, where the caller allows retrying at all.
   */
  public static final int UNAVAILABLE_RETRIES = 2;

  /**
   * Delay between {@link WorkflowAwareness#BPMS_UNAVAILABLE} retries.
   */
  public static final long UNAVAILABLE_RETRY_DELAY_MILLIS = 500;

  private final String workflowModuleId;

  private final String bpmnProcessId;

  /**
   * The cache of workflow &rarr; adapter associations or <code>null</code> for an
   * uncached election (plain walk every time).
   */
  private final WorkflowAdapterCache cache;

  /**
   * The BPMN process ids a hint of this workflow may sit under, this instance's own id
   * first. Set by the process service once the platform integration told it which
   * processes its workflow service serves; until then the own id is the whole list.
   */
  private volatile List<String> bpmnProcessIdsToReadUnder;

  public WorkflowLocator(
      final String workflowModuleId,
      final String bpmnProcessId,
      final WorkflowAdapterCache cache) {

    this.workflowModuleId = workflowModuleId;
    this.bpmnProcessId = bpmnProcessId;
    this.cache = cache;
    this.bpmnProcessIdsToReadUnder = List.of(bpmnProcessId);

  }

  /**
   * Where a hint of this workflow may be looked for, beyond this instance's own BPMN
   * process id.
   * <p>
   * A hint is written under the id of the process which was running when VanillaBP
   * learned the answer, and for a task of a secondary process that is the SECONDARY id,
   * while the application calls its operations on the primary process service. Reading
   * only the own id therefore missed the hint of every workflow a secondary process had
   * a hand in, and with it the reason to wait out an eventually consistent BPMS.
   *
   * @param bpmnProcessIds The ids to read under, the own id first; <code>null</code> or
   *          empty restores the own id alone
   */
  public void setBpmnProcessIdsToReadUnder(
      final List<String> bpmnProcessIds) {

    this.bpmnProcessIdsToReadUnder = (bpmnProcessIds == null) || bpmnProcessIds.isEmpty()
        ? List.of(bpmnProcessId)
        : List.copyOf(bpmnProcessIds);

  }

  /**
   * Records that the given adapter holds the workflow of the given aggregate,
   * called where VanillaBP knows it without probing anybody: after phase two of a
   * start (the adapter which created the instance is recorded with the outbox
   * entry) and on every inbound delivery (a job for that workflow arrived from
   * that BPMS). Both are exactly the moments shortly before an application
   * operates on the workflow, which is what makes the hint worth having.
   * <p>
   * Without a cache the call does nothing.
   *
   * @param workflowAggregateId The ID of the workflow aggregate (any type - its
   *        serialized form is the key)
   * @param adapterId The ID of the adapter holding the workflow
   */
  public void remember(
      final Object workflowAggregateId,
      final String adapterId) {

    if ((cache == null) || (workflowAggregateId == null) || (adapterId == null)) {
      return;
    }
    cache.put(workflowModuleId, bpmnProcessId, workflowAggregateId.toString(), adapterId);

  }

  /**
   * Records that the workflow of the given aggregate ENDED in the given adapter. The
   * hint is kept rather than dropped, because the end is not the last thing which
   * happens to a workflow: a task completed after a timeout, a message correlated by an
   * endpoint which did not learn about the end, an outbox entry dispatched behind it and
   * a read of the viewer API all still ask, and with the hint they ask the adapter which
   * held the workflow and get the warned no-op instead of a walk which fails once the
   * BPMS has forgotten the instance.
   * <p>
   * What the marking changes is the lifetime: a hint which can never become useful again
   * has no business occupying a place - in the shared cache of a cluster, on
   * infrastructure the application pays for - for as long as one of a running workflow.
   * Marking is also why the end no longer REFRESHES the hint, which is what it did while
   * it was an inbound delivery like any other.
   * <p>
   * Without a cache the call does nothing.
   *
   * @param workflowAggregateId The ID of the workflow aggregate (any type - its
   *        serialized form is the key)
   * @param adapterId The ID of the adapter which held the ended workflow
   */
  public void rememberWorkflowEnded(
      final Object workflowAggregateId,
      final String adapterId) {

    if ((cache == null) || (workflowAggregateId == null) || (adapterId == null)) {
      return;
    }
    cache.putEnded(workflowModuleId, bpmnProcessId, workflowAggregateId.toString(), adapterId);

  }

  /**
   * The outcome of a walk which did not fail: either an adapter answered
   * {@link WorkflowAwareness#ACTIVE} (execute the operation there),
   * {@link WorkflowAwareness#COMPLETED} (the operation is a no-op) or every
   * adapter answered {@link WorkflowAwareness#UNKNOWN_TO_BPMS} (the subject is
   * unknown - the caller raises the guiding error).
   * <p>
   * An unknown answer means two different things, and {@link #hintedAdapterId()} is
   * what tells them apart. WITH a hint somebody has seen this workflow on that
   * adapter - VanillaBP started it there or was handed a delivery for it - so
   * "unknown" is a read model which has not caught up, and the operation is planned
   * rather than refused. WITHOUT a hint nobody ever saw the workflow, and that is the
   * wrong id the caller has to hear about at once.
   *
   * @param <A> The aggregate type
   * @param awareness ACTIVE, COMPLETED or UNKNOWN_TO_BPMS (the aggregated verdict)
   * @param adapter The adapter which answered ACTIVE or COMPLETED,
   *        <code>null</code> for UNKNOWN_TO_BPMS
   * @param hintedAdapterId The adapter a cache hint pointed at, or <code>null</code>
   *        if there was none (also <code>null</code> where the hint proved right -
   *        the adapter is then the answer itself)
   */
  public record Location<A>(
                            WorkflowAwareness awareness,
                            MigratableProcessService<A> adapter,
                            String hintedAdapterId) {

    /**
     * @return Whether a hint claims this workflow exists although no adapter reports
     *         it (yet)
     */
    public boolean isUnknownButExpected() {

      return (awareness == WorkflowAwareness.UNKNOWN_TO_BPMS) && (hintedAdapterId != null);

    }

  }

  /**
   * Elects the adapter holding the given workflow: consults the cache first (if
   * any), then probes the given adapters in prioritized order.
   *
   * @param <A> The aggregate type
   * @param prioritizedAdapters The adapters in prioritized order
   * @param probe The operation-specific awareness probe
   * @param workflowAggregateId The ID of the workflow aggregate the probed subject
   *        belongs to (the cache key; may be in the aggregate's original ID type -
   *        its serialized form is used)
   * @param subject A short description of the probed subject (e.g.
   *        <code>"task 'x' of workflow aggregate '42'"</code>) used in log and
   *        error messages
   * @param patience How long this caller may take for an answer
   * @return The location - never <code>null</code>
   * @throws IllegalStateException If an adapter reports itself
   *         {@link WorkflowAwareness#BPMS_UNAVAILABLE}
   */
  public <A> Location<A> locate(
      final List<MigratableProcessService<A>> prioritizedAdapters,
      final Function<MigratableProcessService<A>, WorkflowAwareness> probe,
      final Object workflowAggregateId,
      final String subject,
      final Patience patience) {

    final var serializedAggregateId = workflowAggregateId == null
        ? null
        : workflowAggregateId.toString();

    final var hint = (cache == null) || (serializedAggregateId == null)
        ? null
        : hintOf(serializedAggregateId);

    if (hint != null) {
      final var location = locateViaHint(
          prioritizedAdapters, probe, serializedAggregateId, hint, subject, patience);
      if (location != null) {
        return location;
      }
    }

    return walk(
        prioritizedAdapters,
        probe,
        serializedAggregateId,
        hint == null
            ? null
            : hint.adapterId(),
        subject,
        patience);

  }

  /**
   * What a hint says, together with the BPMN process id it was found under. The second half
   * matters because a hint is repaired where it sits: dropping or marking the own id instead
   * would leave the entry which is read next time saying the old thing.
   *
   * @param bpmnProcessId The id the hint sits under
   * @param adapterId The adapter the hint points at
   */
  private record Hint(
                      String bpmnProcessId,
                      String adapterId) {
  }

  /**
   * The hint of the given workflow, looked for under every BPMN process id this workflow
   * service serves, the own id first. The first one found wins: a workflow is one
   * workflow whichever of those processes was running when the hint was written.
   */
  private Hint hintOf(
      final String serializedAggregateId) {

    for (final var candidate : bpmnProcessIdsToReadUnder) {
      final var adapterId = cache
          .get(workflowModuleId, candidate, serializedAggregateId)
          .orElse(null);
      if (adapterId != null) {
        return new Hint(candidate, adapterId);
      }
    }
    return null;

  }

  /**
   * Probes the adapter a hint points at, before everybody else. Returns
   * <code>null</code> where the hint led nowhere - the caller falls through to the
   * full walk, which re-elects and repairs the entry.
   * <p>
   * The hint is NOT dropped when its adapter answers "unknown": that is the answer of
   * a read model which has not caught up, and dropping the hint would take the very
   * knowledge with it which lets the dispatch wait for that adapter later.
   */
  private <A> Location<A> locateViaHint(
      final List<MigratableProcessService<A>> prioritizedAdapters,
      final Function<MigratableProcessService<A>, WorkflowAwareness> probe,
      final String serializedAggregateId,
      final Hint hint,
      final String subject,
      final Patience patience) {

    final var cachedAdapterId = hint.adapterId();
    final var cachedAdapter = prioritizedAdapters
        .stream()
        .filter(adapter -> adapter.getAdapterId().equals(cachedAdapterId))
        .findFirst()
        .orElse(null);
    if (cachedAdapter == null) {
      // the cached adapter is no longer part of the prioritized configuration -
      // drop the hint and let the full walk elect from the current configuration
      log.debug(
          "Cached adapter '{}' for {} is no longer a prioritized adapter - dropping the hint",
          cachedAdapterId,
          subject);
      cache.invalidate(workflowModuleId, hint.bpmnProcessId(), serializedAggregateId);
      return null;
    }

    final var awareness = patience == Patience.WAIT_FOR_VISIBILITY
        ? probeUntilVisible(cachedAdapter, probe, subject)
        : probeWithRetry(cachedAdapter, probe, subject, patience);
    switch (awareness) {
      case ACTIVE -> {
        return new Location<>(awareness, cachedAdapter, null);
      }
      case COMPLETED -> {
        // the workflow ended - the hint is marked rather than dropped, so the next
        // operation on it is answered by the same adapter (a warned no-op) instead of
        // walking everybody, and it leaves the cache long before a living one would.
        // Marked where the hint sits rather than under this instance's own id: a second
        // entry would leave the one which was read claiming a living workflow
        cache.putEnded(workflowModuleId, hint.bpmnProcessId(), serializedAggregateId, cachedAdapterId);
        return new Location<>(awareness, cachedAdapter, null);
      }
      case UNKNOWN_TO_BPMS -> {
        // hints are not truth: ask everybody else before believing the workflow is
        // nowhere. The hint travels with the answer (see Location)
        log.debug(
            "Adapter '{}' does not report {} - probing the other prioritized adapters",
            cachedAdapterId,
            subject);
        return null;
      }
      case BPMS_UNAVAILABLE -> throw unavailable(cachedAdapter, subject, patience);
    }
    return null;

  }

  private <A> Location<A> walk(
      final List<MigratableProcessService<A>> prioritizedAdapters,
      final Function<MigratableProcessService<A>, WorkflowAwareness> probe,
      final String serializedAggregateId,
      final String hintedAdapterId,
      final String subject,
      final Patience patience) {

    for (final var adapter : prioritizedAdapters) {
      if (adapter.getAdapterId().equals(hintedAdapterId)) {
        // asked already, as the hint
        continue;
      }
      final var awareness = probeWithRetry(adapter, probe, subject, patience);
      switch (awareness) {
        case ACTIVE -> {
          if ((cache != null) && (serializedAggregateId != null)) {
            cache.put(workflowModuleId, bpmnProcessId, serializedAggregateId, adapter.getAdapterId());
          }
          return new Location<>(awareness, adapter, null);
        }
        case COMPLETED -> {
          if ((cache != null) && (serializedAggregateId != null)) {
            // an ended workflow is worth remembering too, for the operation which
            // arrives right behind the end - marked, so the entry lives briefly
            cache.putEnded(workflowModuleId, bpmnProcessId, serializedAggregateId, adapter.getAdapterId());
          }
          return new Location<>(awareness, adapter, null);
        }
        case UNKNOWN_TO_BPMS -> log.debug(
            "Adapter '{}' does not know {} - asking the next prioritized adapter",
            adapter.getAdapterId(),
            subject);
        case BPMS_UNAVAILABLE -> throw unavailable(adapter, subject, patience);
      }
    }
    return new Location<>(WorkflowAwareness.UNKNOWN_TO_BPMS, null, hintedAdapterId);

  }

  private <A> IllegalStateException unavailable(
      final MigratableProcessService<A> adapter,
      final String subject,
      final Patience patience) {

    return new IllegalStateException(
        """
            The BPMS of adapter '%s' is unavailable while locating %s! Falling back to another \
            adapter is not allowed (the unavailable BPMS might hold it), so the operation is \
            refused%s. Retry the business operation once the BPMS is reachable again."""
            .formatted(
                adapter.getAdapterId(),
                subject,
                patience == Patience.NONE
                    ? " at once - repeating the question inside your transaction would hold its "
                        + "database connection for an answer which rarely changes that quickly"
                    : " after %d retries".formatted(UNAVAILABLE_RETRIES)));

  }

  /**
   * Probes an adapter VanillaBP has a reason to believe holds the workflow (a cache
   * hint): an {@link WorkflowAwareness#UNKNOWN_TO_BPMS} answer is asked again until
   * the adapter's {@code workflowVisibilityDelay} window is used up.
   * <p>
   * That window is what an eventually consistent BPMS needs before a workflow it
   * just created shows up in the read model its probe searches - the ordinary "start a
   * workflow, then correlate the message which lets it continue" runs into it. Two
   * things have to hold before anybody waits: a hint has to claim the workflow exists,
   * and the caller has to be one which has nobody to ask the question again for it
   * ({@link Patience#WAIT_FOR_VISIBILITY} - a read of the viewer API).
   */
  private static <A> WorkflowAwareness probeUntilVisible(
      final MigratableProcessService<A> adapter,
      final Function<MigratableProcessService<A>, WorkflowAwareness> probe,
      final String subject) {

    var awareness = probeWithRetry(adapter, probe, subject, Patience.WAIT_FOR_VISIBILITY);
    if (awareness != WorkflowAwareness.UNKNOWN_TO_BPMS) {
      return awareness;
    }

    final var delay = adapter.workflowVisibilityDelay();
    if ((delay == null) || !delay.isWaiting()) {
      return awareness;
    }

    final var deadline = System.nanoTime() + delay.window().toNanos();
    final var intervalMillis = Math.max(1L, delay.interval().toMillis());
    log.debug(
        "Adapter '{}' should hold {} but does not report it (yet) - asking again for up to {}",
        adapter.getAdapterId(),
        subject,
        delay.window());
    while (System.nanoTime() < deadline) {
      try {
        Thread.sleep(intervalMillis);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return awareness;
      }
      awareness = probeWithRetry(adapter, probe, subject, Patience.WAIT_FOR_VISIBILITY);
      if (awareness != WorkflowAwareness.UNKNOWN_TO_BPMS) {
        log.debug(
            "Adapter '{}' reports {} as {} after waiting for its BPMS to catch up",
            adapter.getAdapterId(),
            subject,
            awareness);
        return awareness;
      }
    }
    log.debug(
        "Adapter '{}' still does not know {} after {} - treating the hint as stale",
        adapter.getAdapterId(),
        subject,
        delay.window());
    return awareness;

  }

  private static <A> WorkflowAwareness probeWithRetry(
      final MigratableProcessService<A> adapter,
      final Function<MigratableProcessService<A>, WorkflowAwareness> probe,
      final String subject,
      final Patience patience) {

    var awareness = probe.apply(adapter);
    var retries = patience == Patience.NONE
        ? 0
        : UNAVAILABLE_RETRIES;
    while ((awareness == WorkflowAwareness.BPMS_UNAVAILABLE) && (retries > 0)) {
      log.warn(
          "The BPMS of adapter '{}' is unavailable while locating {} - retrying in {}ms ({} "
              + "retries left; no application transaction is open here)",
          adapter.getAdapterId(),
          subject,
          UNAVAILABLE_RETRY_DELAY_MILLIS,
          retries);
      try {
        Thread.sleep(UNAVAILABLE_RETRY_DELAY_MILLIS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return WorkflowAwareness.BPMS_UNAVAILABLE;
      }
      awareness = probe.apply(adapter);
      --retries;
    }
    return awareness;

  }

}
