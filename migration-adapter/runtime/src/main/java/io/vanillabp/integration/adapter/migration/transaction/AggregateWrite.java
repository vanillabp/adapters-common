package io.vanillabp.integration.adapter.migration.transaction;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.spi.TransactionRunner;

/**
 * Runs work the core brackets in a transaction (processing a task, building the aggregate
 * of a workflow the BPMS started, reporting the end of a workflow, answering an
 * extension) and says what a version conflict on saving the workflow aggregate means.
 * <p>
 * Which transaction that is, the caller says with a {@link TransactionForm}. A task
 * arrives from an adapter which knows whether it calls inside a transaction of its own, so
 * there the form follows the adapter. A handler of an extension is called from wherever
 * the extension was called, which nobody can know in advance, so there the form is to join
 * what runs and to open one only where nothing does.
 * <p>
 * As soon as a BPMN process holds more than one token, two branches write the same
 * workflow aggregate: one in the transaction VanillaBP owns, the other in a
 * transaction the application opens around an API call. An aggregate carrying a
 * version attribute turns that collision into an exception instead of a silent
 * overwrite - and the exception surfaces at the COMMIT VanillaBP performs, where the
 * application cannot catch it.
 * <p>
 * <b>VanillaBP does not retry.</b> A handler may have called a remote API before the
 * commit failed, and a quiet retry inside the framework would repeat that call while
 * hiding that anything went wrong. So the conflict is named by one guiding message and
 * the exception is propagated UNCHANGED: the adapter maps it to its BPMS' retry
 * semantics (a retry until the attempts are used up, then an incident). Where the work
 * ran in a transaction VanillaBP did not open ({@link TransactionForm#CURRENT} for an
 * embedded Camunda 7, or {@link TransactionForm#CURRENT_OR_NEW} meeting a transaction of
 * the application) whoever opened it owns the commit, and VanillaBP never sees the
 * conflict at all.
 * <p>
 * Why a version conflict is reported and propagated unchanged instead of being retried is decision
 * 14 in the repository's DECISIONS.md.
 */
public final class AggregateWrite {

  private static final Logger log = LoggerFactory.getLogger(AggregateWrite.class);

  private AggregateWrite() {
  }

  /**
   * Runs the given work in a transaction and reports a version conflict on the way
   * out.
   *
   * @param <T> The result type
   * @param transactionRunner The platform's transaction runner, which also
   *          classifies the failure (only the platform knows its exceptions)
   * @param transactionForm Which of the three ways of working in a transaction is meant
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowAggregateId The workflow aggregate's ID
   * @param operation What was going on, in a form fitting "... failed": e.g.
   *          "processing task 'approve'"
   * @param work The work to run
   * @return The work's result
   */
  public static <T> T inTransaction(
      final TransactionRunner transactionRunner,
      final TransactionForm transactionForm,
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId,
      final String operation,
      final Supplier<T> work) {

    try {
      return transactionForm.runIn(transactionRunner, work);
    } catch (final RuntimeException failure) {
      if (transactionRunner.isConcurrentModification(failure)) {
        log
            .error(
                """
                    {} of workflow aggregate '{}' (BPMN process '{}' of workflow module '{}') failed \
                    on a version conflict: another writer changed that aggregate meanwhile, so \
                    nothing this run changed was saved. VanillaBP does not retry - the BPMS decides \
                    what happens next (its retries, and an incident once they are used up), and a \
                    repeated run repeats everything the method did outside the transaction as well. \
                    Two writers appear as soon as a workflow has more than one token (e.g. a \
                    non-interrupting boundary event) or the application changes the aggregate \
                    through its own API while the workflow runs, and an extension writing while it \
                    reports is a third way to get there. The wiki page 'Workflow aggregates' names \
                    every pair of writers and the way out of each of them, in the section 'Two \
                    writers on one aggregate'.""",
                capitalized(operation),
                workflowAggregateId == null
                    ? "not assigned yet"
                    : workflowAggregateId,
                bpmnProcessId,
                workflowModuleId);
      }
      throw failure;
    }

  }

  /**
   * The exceptions a persistence layer raises on a version conflict, matched by NAME
   * along the chain of causes - a service both platform implementations of
   * {@link TransactionRunner#isConcurrentModification(Throwable)} use for the part
   * which is not platform-specific at all: JPA reports
   * <code>jakarta.persistence.OptimisticLockException</code> whether it runs under
   * Spring or under JTA, and Hibernate's own
   * <code>org.hibernate.StaleObjectStateException</code> travels as its cause.
   * <p>
   * Names instead of types, because the core is plain Java: it must not gain a
   * dependency on JPA, Hibernate or MongoDB to recognize their exceptions.
   *
   * @param failure The exception the transactional work or its commit produced
   * @return Whether a version conflict is somewhere in the chain of causes
   */
  public static boolean causedByOptimisticLocking(
      final Throwable failure) {

    var candidate = failure;
    while (candidate != null) {
      final var name = candidate
          .getClass()
          .getName();
      if (OPTIMISTIC_LOCKING_EXCEPTIONS.contains(name)) {
        return true;
      }
      candidate = candidate.getCause() == candidate
          ? null
          : candidate.getCause();
    }
    return false;

  }

  private static final java.util.Set<String> OPTIMISTIC_LOCKING_EXCEPTIONS = java.util.Set
      .of(
          "jakarta.persistence.OptimisticLockException",
          "javax.persistence.OptimisticLockException",
          "org.hibernate.StaleObjectStateException",
          "org.hibernate.StaleStateException",
          "io.quarkus.mongodb.panache.common.exception.OptimisticLockException");

  private static String capitalized(
      final String operation) {

    if ((operation == null) || operation.isEmpty()) {
      return "Saving";
    }
    return Character.toUpperCase(operation.charAt(0)) + operation.substring(1);

  }

}
