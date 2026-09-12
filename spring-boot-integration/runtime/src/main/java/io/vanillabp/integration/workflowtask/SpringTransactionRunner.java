package io.vanillabp.integration.workflowtask;

import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.spi.TransactionRunner;

/**
 * The Spring implementation of the core's {@link TransactionRunner} used to run
 * <code>&#64;WorkflowTask</code> handlers: a {@link TransactionTemplate} per form the
 * core asks for, with propagation REQUIRES_NEW, MANDATORY respectively REQUIRED. The
 * {@link PlatformTransactionManager} is resolved lazily - an application without
 * transactional persistence can still boot and gets a guiding message when the
 * first task is processed.
 */
public class SpringTransactionRunner implements TransactionRunner {

  private final ObjectProvider<PlatformTransactionManager> transactionManager;

  private final ThreadLocal<TransactionStatus> currentStatus = new ThreadLocal<>();

  public SpringTransactionRunner(
      final ObjectProvider<PlatformTransactionManager> transactionManager) {

    this.transactionManager = transactionManager;

  }

  /**
   * A runner bound to ONE transaction manager - the shape a mixed-persistence application
   * needs: with a JPA and a MongoDB manager in the same application no manager
   * is unique, so the application attributes its aggregates to runners built from the
   * matching manager through
   * {@link io.vanillabp.integration.spi.TransactionRunnerAware} beans.
   *
   * @param transactionManager The manager covering the aggregates this runner serves
   */
  public SpringTransactionRunner(
      final PlatformTransactionManager transactionManager) {

    this.transactionManager = new ObjectProvider<>() {

      @Override
      public PlatformTransactionManager getObject() {
        return transactionManager;
      }

      @Override
      public PlatformTransactionManager getObject(
          final Object... args) {
        return transactionManager;
      }

      @Override
      public PlatformTransactionManager getIfAvailable() {
        return transactionManager;
      }

      @Override
      public PlatformTransactionManager getIfUnique() {
        return transactionManager;
      }

    };

  }

  /**
   * Registers a {@link org.springframework.transaction.support.TransactionSynchronization}
   * whose <code>beforeCommit</code> runs the check, so a phase-one check of a BPMS
   * adapter runs as late as this platform allows - a throwing check aborts the commit.
   * Without transaction synchronization active the check runs immediately.
   */
  @Override
  public void beforeCommit(
      final Runnable check) {

    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isSynchronizationActive()) {
      check.run();
      return;
    }
    org.springframework.transaction.support.TransactionSynchronizationManager
        .registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {

              @Override
              public void beforeCommit(
                  final boolean readOnly) {

                check.run();

              }

            });

  }


  /**
   * Whether this runner can work at all, which on Spring Boot means a unique
   * {@link PlatformTransactionManager} exists. Asked by
   * {@link io.vanillabp.integration.processservice.SpringTransactionRunnerResolver}: a
   * runner which cannot open a transaction must not be handed out as the platform's
   * default, the startup check reports the situation with every remedy instead.
   *
   * @return Whether a unique transaction manager is available
   */
  public boolean isUsable() {

    return transactionManager.getIfUnique() != null;

  }

  /**
   * The transaction manager this runner uses, for the startup check naming what it
   * covers.
   *
   * @return The manager or <code>null</code> if there is none (or several)
   */
  public PlatformTransactionManager getTransactionManager() {

    return transactionManager.getIfUnique();

  }

  @Override
  public <T> T requireNew(
      final Supplier<T> work) {

    return run(work, TransactionDefinition.PROPAGATION_REQUIRES_NEW);

  }

  @Override
  public <T> T inCurrent(
      final Supplier<T> work) {

    return run(work, TransactionDefinition.PROPAGATION_MANDATORY);

  }

  /**
   * Joins the transaction open on this thread and opens one where none is.
   * <p>
   * The outbox of this platform dispatches inside a transaction of its own where it is
   * gruelbox-based, and the entry is ticked off in that transaction. A second one around
   * the call would commit what the application wrote while the entry is still open, so a
   * dispatch failing afterwards would repeat a call whose changes are already in the
   * database.
   */
  @Override
  public <T> T requireTransaction(
      final Supplier<T> work) {

    return run(work, TransactionDefinition.PROPAGATION_REQUIRED);

  }

  @Override
  public boolean isTransactionActive() {

    return org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive();

  }

  @Override
  public boolean isRollbackOnly() {

    final var status = currentStatus.get();
    // Spring reports the mark for a PARTICIPATING transaction as well, which is the
    // silent case: the commit of the participating transaction returns normally
    // while nothing can be committed
    return (status != null) && status.isRollbackOnly();

  }

  @Override
  public boolean isConcurrentModification(
      final Throwable failure) {

    // OptimisticLockingFailureException is Spring's translation for every
    // persistence technology it integrates - JPA (ObjectOptimisticLockingFailure-
    // Exception) as well as MongoDB. The causes are walked because the conflict
    // arrives wrapped as often as not: the commit of the transaction template wraps
    // what the persistence provider threw while flushing.
    var candidate = failure;
    while (candidate != null) {
      if (candidate instanceof org.springframework.dao.OptimisticLockingFailureException) {
        return true;
      }
      candidate = candidate.getCause() == candidate
          ? null
          : candidate.getCause();
    }
    // a provider exception which never passed Spring's translation (e.g. thrown by
    // an application's own repository code) means the same thing
    return io.vanillabp.integration.adapter.migration.transaction.AggregateWrite
        .causedByOptimisticLocking(failure);

  }

  private <T> T run(
      final Supplier<T> work,
      final int propagation) {

    final var manager = transactionManager.getIfUnique();
    if (manager == null) {
      throw new IllegalStateException(
          """
              No (unique) PlatformTransactionManager is available to process a BPMN task! \
              @WorkflowTask methods load and save the workflow aggregate within one transaction, \
              and a relational database is only one way to get one. To solve this either
              - define a transaction manager covering the persistence of your workflow \
              aggregates: a JPA or JDBC one (e.g. spring-boot-starter-data-jpa with a data \
              source), or a MongoTransactionManager if they live in MongoDB (which needs the \
              deployment to be a replica set),
              - define a bean implementing io.vanillabp.integration.spi.TransactionRunner, which \
              serves every workflow aggregate of this application, or
              - define a bean implementing io.vanillabp.integration.spi.TransactionRunnerAware to \
              provide a runner for a single aggregate (or for an interface all your aggregates \
              implement).
              If several transaction managers exist, none of them is unique - name the one \
              VanillaBP has to use by contributing a TransactionRunner bean built from it.""");
    }
    final var transactionTemplate = new TransactionTemplate(manager);
    transactionTemplate.setPropagationBehavior(propagation);
    return transactionTemplate.execute(status -> {
      // the status is the only way to read the rollback-only mark, and it is
      // available inside the callback only - handlers run on adapter threads, so a
      // thread local is enough to reach it from the core's check
      final var previous = currentStatus.get();
      currentStatus.set(status);
      try {
        return work.get();
      } finally {
        if (previous == null) {
          currentStatus.remove();
        } else {
          currentStatus.set(previous);
        }
      }
    });

  }

}
