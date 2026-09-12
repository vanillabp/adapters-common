package io.vanillabp.integration.adapter.migration.delivery;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;

/**
 * Deletes the records of processed task deliveries once
 * <code>vanillabp.delivery.retention</code> passed - shared by every
 * {@link io.vanillabp.integration.spi.TaskDeliveryLog} implementation of both platforms,
 * since a store differs in HOW it deletes, not in WHEN.
 * <p>
 * The cleanup runs on a private daemon thread, so neither Spring's
 * {@code TaskScheduler} nor the <code>quarkus-scheduler</code> extension is required.
 * It runs once at startup and then {@value #INTERVAL_HOURS}-hourly: records are kept
 * for days, so nothing is gained by looking more often. Deleting is idempotent - the
 * instances of a cluster may run it concurrently.
 * <p>
 * An hour which recorded no delivery deletes nothing and therefore asks nothing: the
 * records of this store only come into being when the application does work, so an
 * application waiting in a timer grows no garbage and its database sees no statement from
 * here. What that leaves behind is the last batch before an application went quiet, kept
 * until it is used again or until it restarts, and keeping a record LONGER is the safe
 * side of the window it guards (decision 42 in the repository's DECISIONS.md).
 */
@Slf4j
public class TaskDeliveryRetentionCleanup {

  /**
   * The fixed delay between two cleanup runs, in hours.
   */
  public static final int INTERVAL_HOURS = 1;

  private final String name;

  private final Duration retention;

  private final Runnable cleanup;

  /**
   * Whether anything was written to this store since the last run. A run without it would
   * delete what a run before it already deleted.
   */
  private final AtomicBoolean recordedSinceTheLastRun = new AtomicBoolean(true);

  private ScheduledExecutorService executor;

  /**
   * @param name Names the store cleaned up (thread name and log messages)
   * @param retention How long a record is kept
   * @param cleanup Deletes the expired records of one store
   */
  public TaskDeliveryRetentionCleanup(
      final String name,
      final Duration retention,
      final Runnable cleanup) {

    this.name = name;
    this.retention = retention;
    this.cleanup = cleanup;

  }

  /**
   * Starts the cleanup. Calling it twice is a no-op - the platforms start it from
   * their own lifecycle hooks.
   */
  public synchronized void start() {

    if (executor != null) {
      return;
    }
    // said once and at INFO, because which of the two retentions this store runs with is
    // the first question a support case about a handler running twice asks
    log
        .info(
            "Records of processed task deliveries in '{}' are kept for {} "
                + "('vanillabp.delivery.retention')",
            name,
            retention);
    executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
      final var thread = new Thread(runnable, "vanillabp-task-deliveries");
      thread.setDaemon(true);
      return thread;
    });
    executor.scheduleWithFixedDelay(
        this::cleanUpWhereSomethingWasRecorded,
        0,
        Duration.ofHours(INTERVAL_HOURS).toMillis(),
        TimeUnit.MILLISECONDS);

  }

  /**
   * Stops the cleanup on shutdown of the application.
   */
  public synchronized void stop() {

    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }

  }

  /**
   * Says that a delivery was written down, which is what gives the next hourly run
   * something to do. Called by the store on every record it writes.
   */
  public void aDeliveryWasRecorded() {

    recordedSinceTheLastRun.set(true);

  }

  /**
   * One run of the cleanup, which deletes nothing and asks nothing where no delivery was
   * recorded since the previous one. It is what the scheduled thread calls, and it is public
   * so a test can drive it without waiting out an hour.
   */
  public void cleanUpWhereSomethingWasRecorded() {

    if (!recordedSinceTheLastRun.getAndSet(false)) {
      return;
    }
    try {
      cleanup.run();
    } catch (final RuntimeException e) {
      // a failing cleanup costs disk space, nothing else - it must not kill the
      // scheduled task (a scheduleWithFixedDelay stops on an escaping exception). What it
      // deleted nothing of is tried again in an hour rather than at the next record
      recordedSinceTheLastRun.set(true);
      log.warn("Could not clean up expired task-delivery records of '{}'", name, e);
    }

  }

}
