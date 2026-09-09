package io.vanillabp.integration.runtime.processservice;

import io.quarkus.arc.Unremovable;
import io.vanillabp.integration.adapter.migration.processservice.TransactionRunnerResolver;
import io.vanillabp.integration.runtime.workflowtask.QuarkusTransactionRunner;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.spi.TransactionRunnerAware;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.TransactionSynchronizationRegistry;

/**
 * Produces the transaction of VanillaBP on Quarkus as CDI beans, the one place this
 * application builds them: the platform's own {@link QuarkusTransactionRunner} and the
 * {@link TransactionRunnerResolver} saying which runner a workflow aggregate is written
 * through.
 * <p>
 * <b>What an extension injects is the resolver, never a runner.</b> Something writing
 * next to a workflow aggregate has to write it in the transaction of that aggregate, and
 * which transaction that is is not the extension's answer: the application may have
 * contributed a {@link TransactionRunnerAware} bean for the aggregate or a runner
 * serving all of them, and a runner of the extension's own would commit its entry
 * separately from the workflow it belongs to. So the extension asks
 * {@link TransactionRunnerResolver#resolveFor(Class)} per aggregate class, exactly as the
 * process services do, and the platform's own runner is what it gets where the
 * application contributed nothing. The Spring Boot integration offers the resolver as
 * <code>vanillaBpTransactionRunnerResolver</code> for the same reason, and the contract
 * is the same on both platforms.
 * <p>
 * <b>Why {@link Typed}.</b> CDI derives the types of a bean from every supertype of what
 * a producer returns, so this bean would carry {@link TransactionRunner} among its types
 * whatever the return type says, and an application contributing a runner bean of its own
 * would make every <code>&#64;Inject TransactionRunner</code> ambiguous - inside the
 * platform and in every extension which believed it could inject one.
 * {@code @Typed(QuarkusTransactionRunner.class)} cuts the bean's types down to that class
 * (plus <code>Object</code>): the platform injects it by its concrete class, an
 * application's runner stays the only bean of the SPI type, and an extension is left with
 * the resolver, which is the answer it wanted anyway.
 */
@ApplicationScoped
public class TransactionRunnerProducer {

  /**
   * Application-provided attributions of aggregates to transaction runners.
   */
  @Inject
  @Any
  Instance<TransactionRunnerAware<?>> transactionRunnerAwares;

  /**
   * Every runner bean of the application - the platform's own among them, which the
   * resolver tells apart by identity.
   */
  @Inject
  @Any
  Instance<TransactionRunner> transactionRunners;

  /**
   * The persistences of the application, which the coverage verdict is read off.
   */
  @Inject
  @Any
  Instance<AggregatePersistenceAware<?>> aggregatePersistences;

  /**
   * Unsatisfied in an application without the MongoDB client extension - see
   * {@link MongoDeploymentProbe}.
   */
  @Inject
  Instance<MongoDeploymentProbe> mongoDeploymentProbes;

  @Inject
  TransactionSynchronizationRegistry transactionRegistry;

  /**
   * Built once for this producer, so both producer methods hand out the SAME runner -
   * the resolver recognizes the platform's runner by identity, and it also answers
   * whether the transaction it opened was marked rollback-only, which belongs to the
   * instance which opened it.
   */
  private QuarkusTransactionRunner platformRunner;

  @PostConstruct
  void buildPlatformRunner() {

    platformRunner = new QuarkusTransactionRunner(transactionRegistry);

  }

  /**
   * The bean is a {@link Singleton} rather than a normal scope on purpose: a
   * pseudo-scoped bean is handed out unproxied, which is what lets
   * {@link QuarkusTransactionRunnerResolver} tell the platform's runner from an
   * application's by identity. A normal scope would put a client proxy in front of it and
   * that comparison would silently stop matching, so the resolution of an application
   * which brought its own runner is held by {@code ApplicationOwnedStoresTest}.
   *
   * @return The platform's runner: JTA, plus the request context Panache and Hibernate
   *         need on the threads an adapter delivers on
   */
  @Produces
  @Singleton
  @Unremovable
  @Typed(QuarkusTransactionRunner.class)
  public QuarkusTransactionRunner vanillaBpPlatformTransactionRunner() {

    return platformRunner;

  }

  /**
   * @return The resolver, injectable by extensions and used by the process services,
   *         the pre-commit registrar and the startup validation
   */
  @Produces
  @Singleton
  @Unremovable
  public TransactionRunnerResolver vanillaBpTransactionRunnerResolver() {

    return new QuarkusTransactionRunnerResolver(
        transactionRunnerAwares, transactionRunners, aggregatePersistences, mongoDeploymentProbes, platformRunner);

  }

}
