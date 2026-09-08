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
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.TransactionSynchronizationRegistry;

/**
 * Produces the transaction of VanillaBP on Quarkus as CDI beans, the one place this
 * application builds them: the platform's own {@link QuarkusTransactionRunner} and the
 * {@link TransactionRunnerResolver} saying which runner a workflow aggregate is written
 * through.
 * <p>
 * An extension writing something of its own next to a workflow aggregate has to write it
 * in the transaction of that aggregate, and which transaction that is, is not the
 * extension's answer: the application may have contributed a
 * {@link TransactionRunnerAware} bean for the aggregate or a runner serving all of them,
 * and a runner of its own would then commit the extension's entry separately from the
 * workflow it belongs to. So the extension injects the resolver and asks it per
 * aggregate class, exactly as the process services do. The Spring Boot integration
 * offers both as beans for the same reason
 * (<code>vanillaBpPlatformTransactionRunner</code>,
 * <code>vanillaBpTransactionRunnerResolver</code>).
 * <p>
 * The platform's runner is produced under its own class rather than under
 * {@link TransactionRunner}: an application may contribute a runner of its own, and
 * every injection point inside the platform would then be ambiguous. As a CDI bean it
 * is injectable as {@link TransactionRunner} all the same, which is what an extension
 * without an aggregate at hand asks for.
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
   * @return The platform's runner: JTA, plus the request context Panache and Hibernate
   *         need on the threads an adapter delivers on
   */
  @Produces
  @Singleton
  @Unremovable
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
