package io.vanillabp.integration.outbox.mongo;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.spi.PhaseTwoCall;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Dispatches committed-but-unprocessed entries of the MongoDB-based phase-two outbox
 * through the core's {@link PhaseTwoRouter}:
 * <ul>
 * <li>right after a commit (triggered by {@link MongoPhaseTwoOutbox}) and</li>
 * <li>by a fixed-delay poller (crash recovery and retries, poll interval configured
 * by <code>vanillabp.outbox.poll-interval</code>) started on
 * {@link ApplicationReadyEvent}.</li>
 * </ul>
 * Due entries (status {@link PhaseTwoOutboxEntry#STATUS_OPEN}) are claimed atomically
 * (find-and-modify incrementing the number of attempts and leasing the entry for one
 * <code>vanillabp.outbox.attempt-frequency</code>), so multiple instances do not
 * dispatch the same entry concurrently. A dispatch which FAILS writes the next attempt
 * itself, at the growing distance of
 * {@link io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties#attemptDelay(int)}
 * - doubling per attempt up to <code>vanillabp.outbox.max-attempt-frequency</code>. On successful dispatch the entry is marked
 * {@link PhaseTwoOutboxEntry#STATUS_DONE} - it stays in the collection for support to
 * read, its <code>dedupKey</code> is replaced by its own id so a repetition of the same
 * operation can be planned again, and it is deleted asynchronously once
 * <code>vanillabp.outbox.retention</code> passed. After
 * <code>vanillabp.outbox.block-after-attempts</code> failed attempts an entry is
 * marked {@link PhaseTwoOutboxEntry#STATUS_BLOCKED} and has to be cleaned up
 * manually.
 * <p>
 * The poller runs on a private single-thread daemon executor - no
 * {@link org.springframework.scheduling.TaskScheduler} bean is registered or used, so
 * an application's own scheduling setup (e.g. <code>&#64;EnableScheduling</code>)
 * stays unaffected.
 */
@RequiredArgsConstructor
@Slf4j
public class MongoPhaseTwoOutboxDispatcher {

  private final MongoTemplate mongoTemplate;

  private final ObjectProvider<PhaseTwoRouter> phaseTwoRouter;

  private final PhaseTwoOutboxProperties properties;

  /**
   * The collection polled for outbox entries - the same one its
   * {@link MongoPhaseTwoOutbox} writes to.
   */
  private final String collection;

  /**
   * What a blocked entry is counted into. A provider and not the bean itself, because
   * Micrometer is optional and the application may bring no metrics at all.
   */
  private final ObjectProvider<io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics> metrics;

  private ScheduledExecutorService poller;

  /**
   * Starts the fixed-delay poller. The first run is executed immediately, dispatching
   * committed-but-unprocessed entries of a previously crashed instance. The listener
   * order guarantees that workflow processing started BEFORE any recovered entry is
   * dispatched (see
   * {@link io.vanillabp.integration.deployment.SpringBootDeploymentService#OUTBOX_DISPATCHER_LISTENER_ORDER}).
   */
  @Order(io.vanillabp.integration.deployment.SpringBootDeploymentService.OUTBOX_DISPATCHER_LISTENER_ORDER)
  @EventListener(ApplicationReadyEvent.class)
  public void startPolling() {

    poller = Executors.newSingleThreadScheduledExecutor(runnable -> {
      final var thread = new Thread(runnable, "vanillabp-outbox");
      thread.setDaemon(true);
      return thread;
    });
    poller.scheduleWithFixedDelay(
        this::poll,
        0,
        properties.getPollInterval().toMillis(),
        TimeUnit.MILLISECONDS);

  }

  @PreDestroy
  public void stopPolling() {

    if (poller != null) {
      poller.shutdown();
      poller = null;
    }

  }

  /**
   * Runs a single poll asynchronously (used right after a commit).
   */
  public void triggerPoll() {

    final var executor = poller;
    if (executor != null) {
      executor.execute(this::poll);
    }

  }

  /**
   * Claims and dispatches all due entries, then deletes DONE entries whose retention
   * passed. Exceptions are caught to keep the poller alive.
   */
  private void poll() {

    try {
      while (true) {
        final var now = Instant.now();
        final var due = Query.query(Criteria
            .where("status")
            .is(PhaseTwoOutboxEntry.STATUS_OPEN)
            .and("nextAttemptAt")
            .lte(now)
            .and("attempts")
            .lt(properties.getBlockAfterAttempts()));
        // claim the entry atomically: increment attempts and set the backoff, so
        // other instances skip it and a failed dispatch is retried automatically
        final var claim = new Update()
            .inc("attempts", 1)
            .set("nextAttemptAt", now.plus(properties.getAttemptFrequency()));
        final var entry = mongoTemplate.findAndModify(
            due, claim, PhaseTwoOutboxEntry.class, collection);
        if (entry == null) {
          break;
        }
        dispatch(entry);
      }
      cleanupDoneEntries();
    } catch (Exception e) {
      log.error("Polling the VanillaBP phase-two outbox failed - will retry", e);
    }

  }

  /**
   * Dispatches a single claimed entry through the core's {@link PhaseTwoRouter}. On
   * success the entry is marked DONE; on failure it stays claimed and is retried
   * after the configured backoff, until it is blocked.
   *
   * @param entry The claimed entry (holding the state before it was claimed)
   */
  private void dispatch(
      final PhaseTwoOutboxEntry entry) {

    try {
      // the entry holds the attempts count BEFORE this claim - a value > 0 means
      // the entry was dispatched before (recovered/retried): the router then runs
      // the START re-dispatch mitigation. The operation travels as its persisted
      // name and is resolved by the router's operation registry
      phaseTwoRouter
          .getObject()
          .dispatch(
              PhaseTwoCall
                  .forDispatch(
                      entry.getOperation(), entry.getWorkflowModuleId(), entry.getBpmnProcessId(), entry
                          .getAggregateId(),
                      entry.getAdapterId(), entry.getArgs()),
              entry.getAttempts() > 0);
      mongoTemplate.updateFirst(
          Query.query(Criteria.where("_id").is(entry.getId())),
          new Update()
              .set("status", PhaseTwoOutboxEntry.STATUS_DONE)
              .set("doneAt", Instant.now())
              // the deduplication window ends with the dispatch: the entry's own id
              // takes the place of the key, which stays readable in idempotencyKey
              .set("dedupKey", entry.getId()),
          collection);
    } catch (Exception e) {
      // the adapter said that repeating cannot help - blocked right away
      // instead of after the configured attempts
      if (io.vanillabp.integration.spi.PhaseTwoPermanentFailure.isPermanent(e)) {
        mongoTemplate.updateFirst(
            Query.query(Criteria.where("_id").is(entry.getId())),
            blockEntry(entry.getId()),
            collection);
        countBlockedEntry(entry.getOperation(), true);
        log.error(
            "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
                + "failed for a reason repeating cannot fix - the outbox entry '{}' is blocked and has "
                + "to be cleaned up manually!",
            entry.getOperation(),
            entry.getBpmnProcessId(),
            entry.getWorkflowModuleId(),
            entry.getAggregateId(),
            entry.getId(),
            e);
        return;
      }
      if (entry.getAttempts() + 1 >= properties.getBlockAfterAttempts()) {
        mongoTemplate.updateFirst(
            Query.query(Criteria.where("_id").is(entry.getId())),
            blockEntry(entry.getId()),
            collection);
        countBlockedEntry(entry.getOperation(), false);
        log.error(
            "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
                + "failed {} times - the outbox entry '{}' is now blocked and has to be cleaned up manually!",
            entry.getOperation(),
            entry.getBpmnProcessId(),
            entry.getWorkflowModuleId(),
            entry.getAggregateId(),
            entry.getAttempts() + 1,
            entry.getId(),
            e);
        return;
      }
      final var retryAfter = io.vanillabp.integration.spi.PhaseTwoRetryLater.retryAfter(e);
      if (retryAfter != null) {
        // the dispatch knows when asking again can help - a workflow the BPMS has not
        // made searchable yet is the case - so the entry waits that long instead of the
        // configured backoff. What ends a reason which never goes away is the attempts
        // counted above, not this due time
        mongoTemplate.updateFirst(
            Query.query(Criteria.where("_id").is(entry.getId())),
            new Update().set("nextAttemptAt", Instant.now().plus(retryAfter)),
            collection);
        log.info(
            "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' cannot "
                + "run yet - the outbox entry '{}' is dispatched again in {} ({} of {} attempts used): {}",
            entry.getOperation(),
            entry.getBpmnProcessId(),
            entry.getWorkflowModuleId(),
            entry.getAggregateId(),
            entry.getId(),
            retryAfter,
            entry.getAttempts() + 1,
            properties.getBlockAfterAttempts(),
            e.getMessage());
      } else {
        // the entry holds the attempts count BEFORE this claim, so attemptDelay(0) is
        // the distance after the first failure: close, because most failures are
        // momentary
        final var retryIn = properties.attemptDelay(entry.getAttempts());
        mongoTemplate.updateFirst(
            Query.query(Criteria.where("_id").is(entry.getId())),
            new Update().set("nextAttemptAt", Instant.now().plus(retryIn)),
            collection);
        log.warn(
            "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
                + "failed - the outbox entry '{}' is dispatched again in {} ({} of {} attempts used)",
            entry.getOperation(),
            entry.getBpmnProcessId(),
            entry.getWorkflowModuleId(),
            entry.getAggregateId(),
            entry.getId(),
            retryIn,
            entry.getAttempts() + 1,
            properties.getBlockAfterAttempts(),
            e);
      }
    }

  }

  /**
   * Counts an entry this store gave up on. The gauge of waiting entries drops at the
   * same moment, so without this counter the only number an operator watches would move
   * as if things had got better.
   *
   * @param operation The persisted name of the operation which was lost
   * @param permanent Whether the adapter said that repeating cannot help
   */
  private void countBlockedEntry(
      final String operation,
      final boolean permanent) {

    io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration
        .vanillaBpMetricsOf(metrics)
        .outboxEntryBlocked(MongoPhaseTwoOutbox.class.getSimpleName(), operation, permanent);

  }

  /**
   * Blocks an entry and releases its <code>dedupKey</code> the way a dispatched entry
   * releases it. Easy to miss and the reason a blocked entry used to be a dead end: the
   * key is what refuses a second schedule of the same operation, so a blocked entry
   * which kept it would silence the very repetition the application needs - it would
   * ask, the outbox would answer no, and that answer looks exactly like a correct
   * deduplication. The document stays for whoever repairs it, and the new attempt of the
   * operation is a document of its own.
   *
   * @param entryId The id of the entry to block
   * @return The update to apply
   */
  private static Update blockEntry(
      final String entryId) {

    return new Update()
        .set("status", PhaseTwoOutboxEntry.STATUS_BLOCKED)
        .set("dedupKey", entryId);

  }

  /**
   * Deletes successfully dispatched (DONE) entries whose retention period passed -
   * the asynchronous cleanup of the "DONE instead of delete" contract.
   */
  private void cleanupDoneEntries() {

    mongoTemplate.remove(
        Query.query(Criteria
            .where("status")
            .is(PhaseTwoOutboxEntry.STATUS_DONE)
            .and("doneAt")
            .lt(Instant.now().minus(properties.getRetention()))),
        collection);

  }

}
