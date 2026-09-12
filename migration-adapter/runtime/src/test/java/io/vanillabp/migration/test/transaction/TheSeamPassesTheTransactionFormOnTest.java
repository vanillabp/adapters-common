package io.vanillabp.migration.test.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.transaction.AggregateWrite;
import io.vanillabp.integration.adapter.migration.transaction.TransactionForm;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Every way of working in a transaction can be ordered at the seam, and the seam picks the
 * method of the runner which means it.
 * <p>
 * The seam used to carry a boolean, which reached two of the three: a caller who wanted to
 * take part in a transaction which might or might not be running had to guess which of the
 * two to ask for. What is pinned here is that the choice travels unchanged, including the
 * form that boolean could not name.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TheSeamPassesTheTransactionFormOnTest {

  /**
   * Writes down which method of the runner the seam reached for.
   */
  private static class RecordingTransactionRunner implements TransactionRunner {

    private final List<String> asked = new ArrayList<>();

    @Override
    public <T> T requireNew(
        final Supplier<T> work) {

      asked.add("requireNew");
      return work.get();

    }

    @Override
    public <T> T inCurrent(
        final Supplier<T> work) {

      asked.add("inCurrent");
      return work.get();

    }

    @Override
    public <T> T requireTransaction(
        final Supplier<T> work) {

      asked.add("requireTransaction");
      return work.get();

    }

    @Override
    public boolean isRollbackOnly() {

      return false;

    }

  }

  private List<String> whatWasAskedFor(
      final TransactionForm form) {

    final var runner = new RecordingTransactionRunner();
    final var returned = AggregateWrite
        .inTransaction(
            runner,
            form,
            "test-module",
            "TestProcess",
            "4711",
            "running the work",
            () -> "the work ran");
    assertEquals("the work ran", returned);
    return runner.asked;

  }

  @Test
  @DisplayName("A new transaction is ordered as a new transaction")
  public void theNewFormReachesRequireNew() {

    assertEquals(List.of("requireNew"), whatWasAskedFor(TransactionForm.NEW));

  }

  @Test
  @DisplayName("The running transaction is ordered as the running one")
  public void theCurrentFormReachesInCurrent() {

    assertEquals(List.of("inCurrent"), whatWasAskedFor(TransactionForm.CURRENT));

  }

  @Test
  @DisplayName("Taking part in whatever runs is ordered as that, and not as one of the other two")
  public void theJoiningFormReachesRequireTransaction() {

    assertEquals(List.of("requireTransaction"), whatWasAskedFor(TransactionForm.CURRENT_OR_NEW));

  }

  @Test
  @DisplayName("What an adapter answers decides between the running transaction and a new one")
  public void whatAnAdapterAnswersPicksTheForm() {

    assertEquals(TransactionForm.CURRENT, TransactionForm.askedForBy(true));
    assertEquals(TransactionForm.NEW, TransactionForm.askedForBy(false));

  }

  @Test
  @DisplayName("A failure of the work reaches the caller whichever form was ordered")
  public void aFailureIsPropagatedForEveryForm() {

    for (final var form : TransactionForm.values()) {
      final var runner = new RecordingTransactionRunner();
      final var failure = new IllegalStateException("the work failed");
      final var thrown = assertThrows(
          IllegalStateException.class,
          () -> AggregateWrite
              .inTransaction(
                  runner,
                  form,
                  "test-module",
                  "TestProcess",
                  "4711",
                  "running the work",
                  () -> {
                    throw failure;
                  }));
      assertEquals(failure, thrown);
    }

  }

}
