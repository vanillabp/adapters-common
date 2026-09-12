package io.vanillabp.migration.test.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.delivery.TaskDeliveryRetentionCleanup;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The retention cleanup of the task-delivery records used to delete hourly whether or not
 * anything had been written, which woke a sleeping application twenty-four times a day for a
 * statement that found nothing. Its records only come into being when the application does
 * work, so an hour which recorded nothing has nothing left to delete that an earlier hour did
 * not already delete.
 */
@ExtendWith(SuppressOutputExtension.class)
public class RetentionCleanupFollowsTheWorkTest {

  private final AtomicInteger deletions = new AtomicInteger();

  private TaskDeliveryRetentionCleanup cleanup = new TaskDeliveryRetentionCleanup(
      "a-table", Duration.ofDays(7), deletions::incrementAndGet);

  @AfterEach
  public void stopTheCleanup() {

    cleanup.stop();

  }

  @Test
  @DisplayName("The first run deletes, because an application which was down may have let records expire")
  public void theRunAtStartupAlwaysDeletes() {

    cleanup.cleanUpWhereSomethingWasRecorded();

    assertEquals(1, deletions.get());

  }

  @Test
  @DisplayName("An hour which recorded nothing deletes nothing and asks nothing")
  public void anHourWithoutARecordIsSilent() {

    cleanup.cleanUpWhereSomethingWasRecorded();
    cleanup.cleanUpWhereSomethingWasRecorded();
    cleanup.cleanUpWhereSomethingWasRecorded();

    assertEquals(1, deletions.get(), "a store nobody wrote to must not be asked again");

  }

  @Test
  @DisplayName("A recorded delivery gives the next hour something to do")
  public void aRecordedDeliveryArmsTheNextRun() {

    cleanup.cleanUpWhereSomethingWasRecorded();
    cleanup.cleanUpWhereSomethingWasRecorded();
    assertEquals(1, deletions.get());

    cleanup.aDeliveryWasRecorded();
    cleanup.cleanUpWhereSomethingWasRecorded();

    assertEquals(2, deletions.get());

  }

  @Test
  @DisplayName("A failed deletion is tried again in the next hour rather than at the next record")
  public void aFailedDeletionIsTriedAgain() {

    cleanup = new TaskDeliveryRetentionCleanup("a-table", Duration.ofDays(7), () -> {
      deletions.incrementAndGet();
      throw new IllegalStateException("the table cannot be reached");
    });

    cleanup.cleanUpWhereSomethingWasRecorded();
    cleanup.cleanUpWhereSomethingWasRecorded();

    assertEquals(2, deletions.get(), "what was not deleted is still there, so the hour after tries again");

  }

}
