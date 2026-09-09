package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCache;
import io.vanillabp.integration.adapter.migration.processservice.WorkflowLocator;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.spi.WorkflowAdapterCache;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The election matrix of the {@link WorkflowLocator}: the walk -
 * ACTIVE stops it, UNKNOWN_TO_BPMS falls through to the next adapter, COMPLETED is
 * reported to the caller, BPMS_UNAVAILABLE never falls back (retry then fail) -
 * plus the optional cache: consulted first, populated on success, repaired on
 * stale hits, never consulted-around on an unavailable cached adapter.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowLocatorTest {

  /**
   * What a probe is asked about. Any scope does here: the adapters of this
   * test answer from what the test told them, not from a deployment.
   */
  private static final io.vanillabp.integration.adapter.spi.WorkflowScope SCOPE = io.vanillabp.integration.adapter.spi.WorkflowScope
      .of("test-module", "TestProcess");

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  /**
   * A second process of the same workflow service, called by the primary one. What its
   * deliveries taught the core is written down under THIS id, while the application
   * addresses the primary service.
   */
  private static final String SECONDARY_PROCESS = "CalledProcess";

  /**
   * A process service answering a fixed awareness sequence (one element per probe
   * call) - the last element repeats.
   */
  static class ProbeAdapter implements MigratableProcessService<Object> {

    private final String adapterId;

    private final List<WorkflowAwareness> answers;

    final AtomicInteger probes = new AtomicInteger();

    /**
     * What this adapter reports as the window its BPMS needs before a workflow it
     * holds becomes findable - none unless a test sets one.
     */
    private io.vanillabp.integration.adapter.spi.WorkflowVisibilityDelay visibilityDelay = io.vanillabp.integration.adapter.spi.WorkflowVisibilityDelay
        .none();

    ProbeAdapter(
        final String adapterId,
        final WorkflowAwareness... answers) {

      this.adapterId = adapterId;
      this.answers = List.of(answers);

    }

    ProbeAdapter waitingFor(
        final java.time.Duration window) {

      this.visibilityDelay = new io.vanillabp.integration.adapter.spi.WorkflowVisibilityDelay(
          window, java.time.Duration.ofMillis(5));
      return this;

    }

    @Override
    public io.vanillabp.integration.adapter.spi.WorkflowVisibilityDelay workflowVisibilityDelay() {
      return visibilityDelay;
    }

    WorkflowAwareness answer() {

      final var index = probes.getAndIncrement();
      return answers.get(Math.min(index, answers.size() - 1));

    }

    @Override
    public String getAdapterId() {
      return adapterId;
    }

    @Override
    public java.util.Map<io.vanillabp.integration.spi.PhaseOperation, io.vanillabp.integration.adapter.spi.PhaseOperationHandler<Object>> phaseOperations() {
      return io.vanillabp.migration.test.TestPhaseOperations.doingNothing();
    }

    @Override
    public WorkflowAwareness awarenessOfTask(
        final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId) {
      return answer();
    }

    @Override
    public WorkflowAwareness awarenessOfWorkflow(
        final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
        final io.vanillabp.integration.spi.AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId) {
      return answer();
    }

    @Override
    public io.vanillabp.integration.adapter.spi.WorkflowAwareness awarenessOfUserTask(
        final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId) {
      return io.vanillabp.integration.adapter.spi.WorkflowAwareness.UNKNOWN_TO_BPMS;
    }

  }

  private static WorkflowLocator.Location<Object> locate(
      final ProbeAdapter... adapters) {

    return locate(null, adapters);

  }

  /**
   * A walk which may take its time - the shape of a read of the viewer API, which is the
   * caller every waiting this class does belongs to.
   */
  private static WorkflowLocator.Location<Object> locate(
      final io.vanillabp.integration.spi.WorkflowAdapterCache cache,
      final ProbeAdapter... adapters) {

    return locate(cache, WorkflowLocator.Patience.WAIT_FOR_VISIBILITY, adapters);

  }

  private static WorkflowLocator.Location<Object> locate(
      final io.vanillabp.integration.spi.WorkflowAdapterCache cache,
      final WorkflowLocator.Patience patience,
      final ProbeAdapter... adapters) {

    return locateOn(new WorkflowLocator(MODULE, PROCESS, cache), patience, adapters);

  }

  /**
   * The locator of the PRIMARY process of a workflow service which also serves a called
   * process - what the platform integration builds and hands the served ids to.
   */
  private static WorkflowLocator primaryLocatorOfAServiceWithACalledProcess(
      final WorkflowAdapterCache cache) {

    final var locator = new WorkflowLocator(MODULE, PROCESS, cache);
    locator.setBpmnProcessIdsToReadUnder(List.of(PROCESS, SECONDARY_PROCESS));
    return locator;

  }

  private static WorkflowLocator.Location<Object> locateOn(
      final WorkflowLocator locator,
      final WorkflowLocator.Patience patience,
      final ProbeAdapter... adapters) {

    return locator
        .locate(
            List.of(adapters),
            adapter -> adapter.awarenessOfTask(SCOPE, "42", "task-1"),
            "42",
            "task 'task-1' of workflow aggregate '42'",
            patience);

  }

  @Test
  @DisplayName("ACTIVE stops the walk - later adapters are not probed")
  public void activeStopsTheWalk() {

    final var first = new ProbeAdapter("first", WorkflowAwareness.ACTIVE);
    final var second = new ProbeAdapter("second", WorkflowAwareness.ACTIVE);

    final var location = locate(first, second);

    assertEquals(WorkflowAwareness.ACTIVE, location.awareness());
    assertSame(first, location.adapter());
    assertEquals(0, second.probes.get(), "the second adapter must not be probed");

  }

  @Test
  @DisplayName("UNKNOWN_TO_BPMS falls through to the next adapter")
  public void unknownFallsThrough() {

    final var first = new ProbeAdapter("first", WorkflowAwareness.UNKNOWN_TO_BPMS);
    final var second = new ProbeAdapter("second", WorkflowAwareness.ACTIVE);

    final var location = locate(first, second);

    assertEquals(WorkflowAwareness.ACTIVE, location.awareness());
    assertSame(second, location.adapter());

  }

  @Test
  @DisplayName("COMPLETED is reported with the answering adapter")
  public void completedIsReported() {

    final var first = new ProbeAdapter("first", WorkflowAwareness.UNKNOWN_TO_BPMS);
    final var second = new ProbeAdapter("second", WorkflowAwareness.COMPLETED);

    final var location = locate(first, second);

    assertEquals(WorkflowAwareness.COMPLETED, location.awareness());
    assertSame(second, location.adapter());

  }

  @Test
  @DisplayName("No adapter knows the task - UNKNOWN_TO_BPMS without an adapter")
  public void nobodyKnows() {

    final var first = new ProbeAdapter("first", WorkflowAwareness.UNKNOWN_TO_BPMS);
    final var second = new ProbeAdapter("second", WorkflowAwareness.UNKNOWN_TO_BPMS);

    final var location = locate(first, second);

    assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, location.awareness());
    assertNull(location.adapter());

  }

  @Test
  @DisplayName("BPMS_UNAVAILABLE recovering within the retries continues the walk")
  public void unavailableRecoversWithinRetries() {

    // first probe unavailable, first retry active
    final var flaky = new ProbeAdapter(
        "flaky", WorkflowAwareness.BPMS_UNAVAILABLE, WorkflowAwareness.ACTIVE);

    final var location = locate(flaky);

    assertEquals(WorkflowAwareness.ACTIVE, location.awareness());
    assertSame(flaky, location.adapter());
    assertEquals(2, flaky.probes.get());

  }

  @Test
  @DisplayName("BPMS_UNAVAILABLE never falls back - retries exhaust and the walk fails naming the adapter")
  public void unavailableNeverFallsBack() {

    final var down = new ProbeAdapter("down-adapter", WorkflowAwareness.BPMS_UNAVAILABLE);
    final var second = new ProbeAdapter("second", WorkflowAwareness.ACTIVE);

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> locate(down, second));

    assertTrue(
        failure.getMessage().contains("down-adapter"),
        "expected the unavailable adapter to be named but got: "
            + failure.getMessage());
    // initial probe + the retries
    assertEquals(1 + WorkflowLocator.UNAVAILABLE_RETRIES, down.probes.get());
    assertEquals(0, second.probes.get(), "falling back to another adapter is forbidden");

  }

  @Test
  @DisplayName("The pluggable probe decides what is asked - awarenessOfWorkflow works the same way")
  public void probeIsPluggable() {

    final var adapter = new ProbeAdapter("first", WorkflowAwareness.ACTIVE);

    final var location = new WorkflowLocator(MODULE, PROCESS, null)
        .locate(
            List.of(adapter),
            candidate -> candidate.awarenessOfWorkflow(SCOPE, null, Map.of()),
            "42",
            "workflow of aggregate '42'",
            WorkflowLocator.Patience.NONE);

    assertEquals(WorkflowAwareness.ACTIVE, location.awareness());

  }

  @Test
  @DisplayName("A successful election populates the cache - the next election probes only the cached adapter")
  public void cachePopulatedOnSuccess() {

    final var cache = new InMemoryWorkflowAdapterCache();
    final var first = new ProbeAdapter("first", WorkflowAwareness.UNKNOWN_TO_BPMS);
    final var second = new ProbeAdapter("second", WorkflowAwareness.ACTIVE);

    // first election: full walk, second adapter wins and is cached
    final var firstLocation = locate(cache, first, second);
    assertSame(second, firstLocation.adapter());
    assertEquals("second", cache.get(MODULE, PROCESS, "42").orElseThrow());

    // second election: the cached adapter is probed directly, the first-priority
    // adapter is not asked again
    final var secondLocation = locate(cache, first, second);
    assertSame(second, secondLocation.adapter());
    assertEquals(1, first.probes.get(), "the cache hit must skip the walk");
    assertEquals(2, second.probes.get());

  }

  @Test
  @DisplayName("A stale cache hit falls through to the full walk and repairs the entry")
  public void staleCacheHitIsRepaired() {

    final var cache = new InMemoryWorkflowAdapterCache();
    cache.put(MODULE, PROCESS, "42", "second");
    final var first = new ProbeAdapter("first", WorkflowAwareness.ACTIVE);
    final var second = new ProbeAdapter("second", WorkflowAwareness.UNKNOWN_TO_BPMS);

    final var location = locate(cache, first, second);

    assertEquals(WorkflowAwareness.ACTIVE, location.awareness());
    assertSame(first, location.adapter());
    // the stale hint is probed once; the walk then stops at the first adapter
    assertEquals(1, second.probes.get());
    assertEquals(1, first.probes.get());
    assertEquals("first", cache.get(MODULE, PROCESS, "42").orElseThrow(), "the entry must be repaired");

  }

  @Test
  @DisplayName("A COMPLETED answer on a cache hit is reported and marks the entry")
  public void completedCacheHitMarksTheEntry() {

    final var cache = new InMemoryWorkflowAdapterCache();
    cache.put(MODULE, PROCESS, "42", "second");
    final var first = new ProbeAdapter("first", WorkflowAwareness.ACTIVE);
    final var second = new ProbeAdapter("second", WorkflowAwareness.COMPLETED);

    final var location = locate(cache, first, second);

    assertEquals(WorkflowAwareness.COMPLETED, location.awareness());
    assertSame(second, location.adapter());
    assertEquals(0, first.probes.get(), "the cache hit must skip the walk");
    // dropping it would send the next operation on that workflow through the full walk,
    // which is where a BPMS that forgot the instance turns a no-op into an exception
    assertEquals(
        "second",
        cache.get(MODULE, PROCESS, "42").orElseThrow(),
        "a completed workflow's entry still answers");
    assertEquals(1, cache.endedSize(), "and lives on the short lifetime of an ended workflow");

  }

  @Test
  @DisplayName("A COMPLETED answer found by the walk is remembered as an ended workflow")
  public void completedWalkMarksTheEntry() {

    final var cache = new InMemoryWorkflowAdapterCache();
    final var first = new ProbeAdapter("first", WorkflowAwareness.UNKNOWN_TO_BPMS);
    final var second = new ProbeAdapter("second", WorkflowAwareness.COMPLETED);

    final var location = locate(cache, first, second);

    assertEquals(WorkflowAwareness.COMPLETED, location.awareness());
    assertEquals(
        "second",
        cache.get(MODULE, PROCESS, "42").orElseThrow(),
        "the operation arriving right behind the end skips the walk");
    assertEquals(1, cache.endedSize());

  }

  @Test
  @DisplayName("BPMS_UNAVAILABLE on a cached adapter never falls through - retry then fail naming the adapter")
  public void unavailableCachedAdapterNeverFallsThrough() {

    final var cache = new InMemoryWorkflowAdapterCache();
    cache.put(MODULE, PROCESS, "42", "down-adapter");
    final var down = new ProbeAdapter("down-adapter", WorkflowAwareness.BPMS_UNAVAILABLE);
    final var second = new ProbeAdapter("second", WorkflowAwareness.ACTIVE);

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> locate(cache, down, second));

    assertTrue(
        failure.getMessage().contains("down-adapter"),
        "expected the unavailable adapter to be named but got: "
            + failure.getMessage());
    assertEquals(1 + WorkflowLocator.UNAVAILABLE_RETRIES, down.probes.get());
    assertEquals(0, second.probes.get(), "falling back/through on unavailability is forbidden");
    assertEquals(
        "down-adapter",
        cache.get(MODULE, PROCESS, "42").orElseThrow(),
        "an unavailable adapter's entry is kept - it most probably still holds the workflow");

  }

  @Test
  @DisplayName("A cached adapter no longer prioritized drops the hint and elects from the current configuration")
  public void cachedAdapterNoLongerConfigured() {

    final var cache = new InMemoryWorkflowAdapterCache();
    cache.put(MODULE, PROCESS, "42", "removed-adapter");
    final var first = new ProbeAdapter("first", WorkflowAwareness.ACTIVE);

    final var location = locate(cache, first);

    assertEquals(WorkflowAwareness.ACTIVE, location.awareness());
    assertSame(first, location.adapter());
    assertEquals("first", cache.get(MODULE, PROCESS, "42").orElseThrow(), "the entry must be repaired");

  }


  @Test
  @DisplayName("A hinted adapter which does not know the workflow YET is asked again until it does")
  public void hintedAdapterIsAskedAgainWithinItsVisibilityWindow() {

    // the everyday sequence on an eventually consistent BPMS: the workflow was
    // started moments ago (which is why the hint exists), and the read model the
    // probe searches has not caught up
    final var cache = new InMemoryWorkflowAdapterCache();
    cache.put(MODULE, PROCESS, "42", "remote");
    final var remote = new ProbeAdapter(
        "remote", WorkflowAwareness.UNKNOWN_TO_BPMS, WorkflowAwareness.UNKNOWN_TO_BPMS, WorkflowAwareness.ACTIVE)
        .waitingFor(java.time.Duration.ofSeconds(2));

    final var location = locate(cache, remote);

    assertEquals(WorkflowAwareness.ACTIVE, location.awareness());
    assertSame(remote, location.adapter());
    assertEquals(3, remote.probes.get(), "the probe is repeated until the workflow shows up");
    assertEquals(
        "remote", cache.get(MODULE, PROCESS, "42").orElseThrow(), "the hint stays");

  }

  @Test
  @DisplayName("A workflow nobody knows fails IMMEDIATELY - no window is waited out without a hint")
  public void unknownWorkflowWithoutHintFailsFast() {

    // a wrong ID must not turn into a timeout: the waiting happens on a hinted
    // adapter only, and there is no hint here
    final var remote = new ProbeAdapter("remote", WorkflowAwareness.UNKNOWN_TO_BPMS)
        .waitingFor(java.time.Duration.ofSeconds(30));

    final var startedAt = System.nanoTime();
    final var location = locate(new InMemoryWorkflowAdapterCache(), remote);
    final var elapsed = java.time.Duration.ofNanos(System.nanoTime() - startedAt);

    assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, location.awareness());
    assertEquals(1, remote.probes.get(), "the adapter is asked exactly once");
    assertTrue(
        elapsed.toSeconds() < 5,
        "the walk must not wait for the window, but took "
            + elapsed);

  }

  @Test
  @DisplayName("A hint which stays unknown for the whole window falls through to the walk")
  public void hintedAdapterGivingUpFallsThroughToTheWalk() {

    final var cache = new InMemoryWorkflowAdapterCache();
    cache.put(MODULE, PROCESS, "42", "remote");
    final var remote = new ProbeAdapter("remote", WorkflowAwareness.UNKNOWN_TO_BPMS)
        .waitingFor(java.time.Duration.ofMillis(30));
    final var embedded = new ProbeAdapter("embedded", WorkflowAwareness.ACTIVE);

    final var location = locate(cache, remote, embedded);

    assertEquals(WorkflowAwareness.ACTIVE, location.awareness());
    assertSame(embedded, location.adapter(), "the workflow really lives in the other BPMS");
    assertEquals(
        "embedded", cache.get(MODULE, PROCESS, "42").orElseThrow(), "the stale hint is repaired");

  }

  @Test
  @DisplayName("An unavailable BPMS keeps its own contract - the visibility window changes nothing")
  public void unavailableAdapterIsNotSubjectToTheWindow() {

    final var cache = new InMemoryWorkflowAdapterCache();
    cache.put(MODULE, PROCESS, "42", "remote");
    final var remote = new ProbeAdapter("remote", WorkflowAwareness.BPMS_UNAVAILABLE)
        .waitingFor(java.time.Duration.ofSeconds(30));

    assertThrows(
        IllegalStateException.class,
        () -> locate(cache, remote));
    // the fixed unavailable-policy applies (1 probe + 2 retries), and nothing waits
    // for a workflow to become visible: an unreachable BPMS answers nothing at all
    assertEquals(3, remote.probes.get());

  }

  @Test
  @DisplayName("Phase one refuses an unavailable BPMS at once - no retry keeps the transaction open")
  public void withoutPatienceAnUnavailableBpmsFailsAtOnce() {

    final var down = new ProbeAdapter("down-adapter", WorkflowAwareness.BPMS_UNAVAILABLE);

    final var startedAt = System.nanoTime();
    final var failure = assertThrows(
        IllegalStateException.class,
        () -> locate(null, WorkflowLocator.Patience.NONE, down));
    final var elapsed = java.time.Duration.ofNanos(System.nanoTime() - startedAt);

    assertEquals(1, down.probes.get(), "the adapter is asked exactly once");
    assertTrue(
        elapsed.toMillis() < WorkflowLocator.UNAVAILABLE_RETRY_DELAY_MILLIS,
        "nothing may sleep here, but the walk took "
            + elapsed);
    assertTrue(failure.getMessage().contains("down-adapter"), failure.getMessage());

  }

  @Test
  @DisplayName("Phase one does not wait for a read model - the hint travels with the unknown answer")
  public void withoutPatienceAHintedAdapterIsAskedOnce() {

    final var cache = new InMemoryWorkflowAdapterCache();
    cache.put(MODULE, PROCESS, "42", "remote");
    final var remote = new ProbeAdapter("remote", WorkflowAwareness.UNKNOWN_TO_BPMS)
        .waitingFor(java.time.Duration.ofSeconds(30));

    final var startedAt = System.nanoTime();
    final var location = locate(cache, WorkflowLocator.Patience.NONE, remote);
    final var elapsed = java.time.Duration.ofNanos(System.nanoTime() - startedAt);

    assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, location.awareness());
    assertEquals(1, remote.probes.get(), "the adapter is asked exactly once");
    assertTrue(
        elapsed.toSeconds() < 5,
        "the caller's transaction must not wait for the window, but the walk took "
            + elapsed);
    // what the caller needs to tell "not visible yet" from "nobody knows it"
    assertTrue(location.isUnknownButExpected());
    assertEquals("remote", location.hintedAdapterId());
    assertEquals(
        "remote",
        cache.get(MODULE, PROCESS, "42").orElseThrow(),
        "the hint stays - it is what lets the dispatch wait for that very adapter");

  }

  @Test
  @DisplayName("An unknown workflow nobody hinted at is unknown, not 'not visible yet'")
  public void withoutAHintAnUnknownWorkflowIsNotExpected() {

    final var remote = new ProbeAdapter("remote", WorkflowAwareness.UNKNOWN_TO_BPMS);

    final var location = locate(new InMemoryWorkflowAdapterCache(), WorkflowLocator.Patience.NONE, remote);

    assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, location.awareness());
    assertFalse(location.isUnknownButExpected());
    assertEquals(null, location.hintedAdapterId());

  }

  @Test
  @DisplayName("A task operation of the dispatch retries an unavailable BPMS but waits for no read model")
  public void retryingPatienceDoesNotWaitForVisibility() {

    final var cache = new InMemoryWorkflowAdapterCache();
    cache.put(MODULE, PROCESS, "42", "remote");
    final var remote = new ProbeAdapter("remote", WorkflowAwareness.UNKNOWN_TO_BPMS)
        .waitingFor(java.time.Duration.ofSeconds(30));

    final var startedAt = System.nanoTime();
    final var location = locate(cache, WorkflowLocator.Patience.RETRY_UNAVAILABLE, remote);
    final var elapsed = java.time.Duration.ofNanos(System.nanoTime() - startedAt);

    assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, location.awareness());
    assertEquals(1, remote.probes.get(), "a task the BPMS does not know stays unknown");
    assertTrue(elapsed.toSeconds() < 5, "nothing waits for a read model here, but it took "
        + elapsed);

  }

  @Test
  @DisplayName("remember() records what VanillaBP knows without probing anybody")
  public void rememberRecordsTheAdapterWithoutProbing() {

    final var cache = new InMemoryWorkflowAdapterCache();

    new WorkflowLocator(MODULE, PROCESS, cache).remember(42L, "remote");

    assertEquals("remote", cache.get(MODULE, PROCESS, "42").orElseThrow());
    assertEquals(0, cache.endedSize(), "a running workflow is not marked");

  }

  @Test
  @DisplayName("A cache which does not know about ended workflows keeps working unchanged")
  public void aCacheWithoutTheMarkKeepsWorking() {

    // exactly the three methods an application implemented before ended workflows were
    // marked - it has to compile and to behave as it always did
    final var cache = new WorkflowAdapterCache() {

      private final java.util.Map<String, String> entries = new java.util.HashMap<>();

      @Override
      public java.util.Optional<String> get(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String workflowAggregateId) {
        return java.util.Optional.ofNullable(entries.get(workflowAggregateId));
      }

      @Override
      public void put(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String workflowAggregateId,
          final String adapterId) {
        entries.put(workflowAggregateId, adapterId);
      }

      @Override
      public void invalidate(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String workflowAggregateId) {
        entries.remove(workflowAggregateId);
      }

    };

    new WorkflowLocator(MODULE, PROCESS, cache).rememberWorkflowEnded(42L, "remote");

    assertEquals(
        "remote",
        cache.get(MODULE, PROCESS, "42").orElseThrow(),
        "the mark falls back to an ordinary hint, which is what such a cache always stored");

  }

  @Test
  @DisplayName("The end of a workflow marks the hint instead of refreshing it")
  public void theEndMarksTheHint() {

    final var cache = new InMemoryWorkflowAdapterCache();
    final var locator = new WorkflowLocator(MODULE, PROCESS, cache);

    locator.remember(42L, "remote");
    locator.rememberWorkflowEnded(42L, "remote");

    assertEquals(
        "remote",
        cache.get(MODULE, PROCESS, "42").orElseThrow(),
        "an operation which crossed the end still finds the adapter which held the workflow");
    assertEquals(1, cache.endedSize(), "and the hint is on its way out");

  }

  @Test
  @DisplayName("A hint a called process wrote is read by the primary service, and its window is waited out")
  public void aHintOfACalledProcessIsReadAndItsWindowIsWaitedOut() {

    // the delivery of a task of the called process wrote the hint, so it sits under THAT
    // id; the application pushes its changed aggregate through the primary service, and
    // the read model of the BPMS has not caught up
    final var cache = new InMemoryWorkflowAdapterCache();
    cache.put(MODULE, SECONDARY_PROCESS, "42", "remote");
    final var remote = new ProbeAdapter(
        "remote", WorkflowAwareness.UNKNOWN_TO_BPMS, WorkflowAwareness.UNKNOWN_TO_BPMS, WorkflowAwareness.ACTIVE)
        .waitingFor(java.time.Duration.ofSeconds(2));

    final var location = locateOn(
        primaryLocatorOfAServiceWithACalledProcess(cache), WorkflowLocator.Patience.WAIT_FOR_VISIBILITY, remote);

    assertEquals(WorkflowAwareness.ACTIVE, location.awareness());
    assertSame(remote, location.adapter());
    assertTrue(remote.probes.get() > 1, "without the hint nothing would have asked a second time");

  }

  @Test
  @DisplayName("Without the hint of a called process the walk answers unknown, and nothing waits")
  public void withoutTheServedIdsAHintOfACalledProcessIsNotRead() {

    final var cache = new InMemoryWorkflowAdapterCache();
    cache.put(MODULE, SECONDARY_PROCESS, "42", "remote");
    final var remote = new ProbeAdapter("remote", WorkflowAwareness.UNKNOWN_TO_BPMS)
        .waitingFor(java.time.Duration.ofSeconds(2));

    final var location = locate(cache, remote);

    assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, location.awareness());
    assertFalse(
        location.isUnknownButExpected(),
        "a service which serves the primary process alone has no business reading another "
            + "process' hints");
    assertEquals(1, remote.probes.get(), "and nothing waits for a workflow nobody claims");

  }

  @Test
  @DisplayName("A stale hint of a called process is repaired where it lives")
  public void aStaleHintOfACalledProcessIsRepairedWhereItLives() {

    final var cache = new InMemoryWorkflowAdapterCache();
    cache.put(MODULE, SECONDARY_PROCESS, "42", "removed-adapter");
    final var first = new ProbeAdapter("first", WorkflowAwareness.ACTIVE);

    final var location = locateOn(
        primaryLocatorOfAServiceWithACalledProcess(cache), WorkflowLocator.Patience.WAIT_FOR_VISIBILITY, first);

    assertEquals(WorkflowAwareness.ACTIVE, location.awareness());
    assertTrue(
        cache.get(MODULE, SECONDARY_PROCESS, "42").isEmpty(),
        "the entry which was read is the entry which is dropped - repairing another one would "
            + "leave this hint to be read again");
    assertEquals(
        "first",
        cache.get(MODULE, PROCESS, "42").orElseThrow(),
        "what the walk elected is remembered where this service writes");

  }

  @Test
  @DisplayName("A workflow which ended is marked under the id its hint sits at")
  public void anEndedWorkflowIsMarkedWhereItsHintLives() {

    final var cache = new InMemoryWorkflowAdapterCache();
    cache.put(MODULE, SECONDARY_PROCESS, "42", "remote");
    final var remote = new ProbeAdapter("remote", WorkflowAwareness.COMPLETED);

    final var location = locateOn(
        primaryLocatorOfAServiceWithACalledProcess(cache), WorkflowLocator.Patience.WAIT_FOR_VISIBILITY, remote);

    assertEquals(WorkflowAwareness.COMPLETED, location.awareness());
    assertEquals(
        "remote",
        cache.get(MODULE, SECONDARY_PROCESS, "42").orElseThrow(),
        "an operation arriving behind the end still gets the warned no-op");
    assertEquals(1, cache.endedSize(), "and one entry is marked, not two");

  }

}
