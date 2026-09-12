package io.vanillabp.integration.adapter.migration.transaction;

import java.util.function.Supplier;

import io.vanillabp.integration.spi.TransactionRunner;

/**
 * Which of the three ways of working in a transaction a caller of
 * {@link AggregateWrite#inTransaction} means. Each of them is one method of
 * {@link TransactionRunner}, and the seam passes the choice on without judging it.
 * <p>
 * There used to be a boolean here, which could name two of the three. That left every
 * caller to know in advance whether a transaction was running, because the form for "join
 * one if it runs" could not be ordered at all. Which transaction a call into the
 * application gets is decision 44 in the repository's DECISIONS.md.
 */
public enum TransactionForm {

  /**
   * A transaction of its own, suspending whatever runs. What the work writes commits for
   * itself, so it stays where a surrounding transaction is rolled back.
   */
  NEW {

    @Override
    <T> T runIn(
        final TransactionRunner transactionRunner,
        final Supplier<T> work) {

      return transactionRunner.requireNew(work);

    }

  },

  /**
   * The transaction already running, and nothing else will do. An embedded BPMS calls
   * inside its own transaction, and the engine owns the commit there. A thread without a
   * transaction is a mistake the runner reports.
   */
  CURRENT {

    @Override
    <T> T runIn(
        final TransactionRunner transactionRunner,
        final Supplier<T> work) {

      return transactionRunner.inCurrent(work);

    }

  },

  /**
   * The transaction already running, or one VanillaBP opens where none runs. The form
   * which needs no knowledge about the caller, which is why it is what a handler of an
   * extension gets.
   */
  CURRENT_OR_NEW {

    @Override
    <T> T runIn(
        final TransactionRunner transactionRunner,
        final Supplier<T> work) {

      return transactionRunner.requireTransaction(work);

    }

  };

  /**
   * Runs the given work the way this form says.
   *
   * @param <T> The result type
   * @param transactionRunner The runner serving the workflow aggregate
   * @param work The work to run
   * @return The work's result
   */
  abstract <T> T runIn(
      TransactionRunner transactionRunner,
      Supplier<T> work);

  /**
   * The form an adapter asks for by saying whether it calls inside a transaction of its
   * own. An embedded BPMS answers yes and owns the commit; every other adapter calls from
   * a worker thread where nothing runs, and gets a transaction from VanillaBP.
   *
   * @param runInCurrentTransaction What the adapter's invocation context answers
   * @return The form to use
   */
  public static TransactionForm askedForBy(
      final boolean runInCurrentTransaction) {

    return runInCurrentTransaction
        ? CURRENT
        : NEW;

  }

}
