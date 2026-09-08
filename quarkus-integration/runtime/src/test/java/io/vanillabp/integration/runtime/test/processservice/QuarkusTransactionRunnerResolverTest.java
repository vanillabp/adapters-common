package io.vanillabp.integration.runtime.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.processservice.TransactionCoverage;
import io.vanillabp.integration.runtime.persistence.PanacheMongoActiveRecordAggregatePersistence;
import io.vanillabp.integration.runtime.processservice.MongoDeploymentProbe;
import io.vanillabp.integration.runtime.processservice.QuarkusTransactionRunnerResolver;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.spi.TransactionRunnerAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Instance;

/**
 * Which runner serves an aggregate on Quarkus. The platform's own runner is always
 * usable here (JTA is a hard dependency of this extension), so what the resolution has to get
 * right is the order and the ambiguity.
 */
@ExtendWith(SuppressOutputExtension.class)
public class QuarkusTransactionRunnerResolverTest {

  private interface HasWorkflowState {
  }

  private static class OrderAggregate implements HasWorkflowState {
  }

  /**
   * A runner an application wrote, and the client proxy the bean container puts in front
   * of it (its name is what the message used to carry).
   */
  private static class UnitOfWork implements TransactionRunner {

    @Override
    public <T> T requireNew(
        final Supplier<T> work) {
      return work.get();
    }

    @Override
    public <T> T inCurrent(
        final Supplier<T> work) {
      return work.get();
    }

    @Override
    public boolean isRollbackOnly() {
      return false;
    }

  }

  private static class UnitOfWork_ClientProxy extends UnitOfWork {
  }

  private static class OrderTransactions implements TransactionRunnerAware<OrderAggregate> {

    @Override
    public Class<OrderAggregate> getAggregateClass() {
      return OrderAggregate.class;
    }

    @Override
    public TransactionRunner getTransactionRunner() {
      return APPLICATION_RUNNER;
    }

  }

  private static class OrderTransactions_ClientProxy extends OrderTransactions {
  }

  private static final TransactionRunner PLATFORM_RUNNER = passThroughRunner();

  private static final TransactionRunner APPLICATION_RUNNER = passThroughRunner();

  private static TransactionRunner passThroughRunner() {

    return new TransactionRunner() {

      @Override
      public <T> T requireNew(
          final Supplier<T> work) {
        return work.get();
      }

      @Override
      public <T> T inCurrent(
          final Supplier<T> work) {
        return work.get();
      }

      @Override
      public boolean isRollbackOnly() {
        return false;
      }

    };

  }

  private static TransactionRunnerAware<?> awareFor(
      final Class<?> aggregateClass,
      final TransactionRunner runner) {

    return new TransactionRunnerAware<Object>() {

      @SuppressWarnings("unchecked")
      @Override
      public Class<Object> getAggregateClass() {
        return (Class<Object>) aggregateClass;
      }

      @Override
      public TransactionRunner getTransactionRunner() {
        return runner;
      }

    };

  }

  private QuarkusTransactionRunnerResolver resolver(
      final List<TransactionRunnerAware<?>> awares,
      final List<TransactionRunner> runners) {

    return new QuarkusTransactionRunnerResolver(
        InstanceDouble.of(awares), InstanceDouble.of(runners), InstanceDouble
            .of(List.of()), noProbe(), PLATFORM_RUNNER);

  }

  @Test
  @DisplayName("The platform's own runner is a bean, and is not mistaken for one of the application")
  public void thePlatformsOwnBeanIsNotTheApplicationsAnswer() {

    // TransactionRunnerProducer produces it, so it shows up among the runner beans of
    // the application. Taking it for one would report the coverage of an aggregate as
    // the application's business, and the MongoDB verdict below would never be written
    final var testee = resolver(List.of(), List.of(PLATFORM_RUNNER));

    assertSame(PLATFORM_RUNNER, testee.resolveFor(OrderAggregate.class));
    assertEquals("the JTA transaction of Quarkus", testee.describeResolutionFor(OrderAggregate.class));

  }

  @Test
  @DisplayName("A runner of the application next to the platform's own bean still wins")
  public void anApplicationRunnerNextToThePlatformsBeanWins() {

    final var applicationRunner = new UnitOfWork();

    final var testee = resolver(List.of(), List.of(PLATFORM_RUNNER, applicationRunner));

    assertSame(applicationRunner, testee.resolveFor(OrderAggregate.class));

  }

  @Test
  @DisplayName("A runner bean is named by the class it was declared as, not by its proxy")
  public void plainRunnerBeanIsNamedByItsDeclaredClass() {

    final var testee = new QuarkusTransactionRunnerResolver(
        InstanceDouble.of(List.of()), InstanceDouble
            .ofDeclaredAs(
                List.of(new UnitOfWork_ClientProxy()),
                List.of(UnitOfWork.class)), InstanceDouble
                    .of(List.of()), noProbe(), PLATFORM_RUNNER);

    assertEquals(
        "the TransactionRunner bean '%s' of the application".formatted(UnitOfWork.class.getName()),
        testee.describeResolutionFor(OrderAggregate.class));

  }

  @Test
  @DisplayName("An aware bean is named by the class it was declared as, too")
  public void awareBeanIsNamedByItsDeclaredClass() {

    final var testee = new QuarkusTransactionRunnerResolver(
        InstanceDouble
            .ofDeclaredAs(
                List.of(new OrderTransactions_ClientProxy()),
                List.of(OrderTransactions.class)), InstanceDouble.of(List.of()), InstanceDouble
                    .of(List.of()), noProbe(), PLATFORM_RUNNER);

    assertEquals(
        "the TransactionRunnerAware bean '%s' of the application"
            .formatted(OrderTransactions.class.getName()),
        testee.describeResolutionFor(OrderAggregate.class));

  }

  @Test
  @DisplayName("Both ambiguity messages name declared classes as well")
  public void ambiguityMessagesNameDeclaredClasses() {

    final var tiedAwares = new QuarkusTransactionRunnerResolver(
        InstanceDouble
            .ofDeclaredAs(
                List.of(
                    new OrderTransactions_ClientProxy(),
                    new OrderTransactions_ClientProxy()),
                List.of(OrderTransactions.class, OrderTransactions.class)), InstanceDouble
                    .of(List.of()), InstanceDouble
                        .of(List.of()), noProbe(), PLATFORM_RUNNER);
    final var awareFailure = assertThrowsExactly(
        IllegalStateException.class,
        () -> tiedAwares.resolveFor(OrderAggregate.class));
    assertTrue(
        awareFailure.getMessage().contains(OrderTransactions.class.getName()),
        awareFailure.getMessage());
    assertFalse(awareFailure.getMessage().contains("_ClientProxy"), awareFailure.getMessage());

    final var severalRunners = new QuarkusTransactionRunnerResolver(
        InstanceDouble.of(List.of()), InstanceDouble
            .ofDeclaredAs(
                List.of(new UnitOfWork_ClientProxy(), new UnitOfWork_ClientProxy()),
                List.of(UnitOfWork.class, UnitOfWork.class)), InstanceDouble
                    .of(List.of()), noProbe(), PLATFORM_RUNNER);
    final var runnerFailure = assertThrowsExactly(
        IllegalStateException.class,
        () -> severalRunners.resolveFor(OrderAggregate.class));
    assertTrue(
        runnerFailure.getMessage().contains(UnitOfWork.class.getName()),
        runnerFailure.getMessage());
    assertFalse(runnerFailure.getMessage().contains("_ClientProxy"), runnerFailure.getMessage());

  }

  @Test
  @DisplayName("Without bean metadata the runtime class is named - nothing is guessed away")
  public void withoutBeanMetadataTheRuntimeClassIsNamed() {

    final var testee = new QuarkusTransactionRunnerResolver(
        InstanceDouble.of(List.of()), InstanceDouble
            .ofWithoutMetadata(List.of(new UnitOfWork_ClientProxy())), InstanceDouble
                .of(List.of()), noProbe(), PLATFORM_RUNNER);

    assertEquals(
        "the TransactionRunner bean '%s' of the application"
            .formatted(UnitOfWork_ClientProxy.class.getName()),
        testee.describeResolutionFor(OrderAggregate.class));

  }

  @Test
  @DisplayName("Without any bean of the application the JTA transaction of Quarkus is used")
  public void platformRunnerIsTheDefault() {

    final var testee = resolver(List.of(), List.of());

    assertSame(PLATFORM_RUNNER, testee.resolveFor(OrderAggregate.class));
    assertTrue(
        testee.describeResolutionFor(OrderAggregate.class).contains("JTA"),
        testee.describeResolutionFor(OrderAggregate.class));
    // no aggregate persistence to judge, so no verdict is invented
    assertEquals(
        TransactionCoverage.Verdict.UNKNOWN,
        testee.coverageOf(OrderAggregate.class).verdict());

  }

  @Test
  @DisplayName("A runner bean of the application beats the platform's, an aware bean beats both")
  public void applicationBeansWin() {

    final var withRunnerBean = resolver(List.of(), List.of(APPLICATION_RUNNER));
    assertSame(APPLICATION_RUNNER, withRunnerBean.resolveFor(OrderAggregate.class));

    final var awareRunner = passThroughRunner();
    final var withAware = resolver(
        List.of(awareFor(HasWorkflowState.class, awareRunner)),
        List.of(APPLICATION_RUNNER));
    assertSame(awareRunner, withAware.resolveFor(OrderAggregate.class));
    // the application owns the transaction, so the platform says nothing about it
    assertEquals(
        TransactionCoverage.Verdict.UNKNOWN,
        withAware.coverageOf(OrderAggregate.class).verdict());

  }

  @Test
  @DisplayName("Two aware beans at the same distance end the startup naming both")
  public void ambiguousAwareBeansEndTheStartup() {

    final var aggregateSpecific = passThroughRunner();
    final var testee = resolver(
        List
            .of(
                awareFor(HasWorkflowState.class, APPLICATION_RUNNER),
                awareFor(OrderAggregate.class, aggregateSpecific)),
        List.of());

    // the bean naming the aggregate itself is closer than the one naming its interface
    assertSame(aggregateSpecific, testee.resolveFor(OrderAggregate.class));

    final var tied = resolver(
        List
            .of(
                awareFor(HasWorkflowState.class, APPLICATION_RUNNER),
                awareFor(HasWorkflowState.class, PLATFORM_RUNNER)),
        List.of());
    final var failure = assertThrowsExactly(
        IllegalStateException.class,
        () -> tied.resolveFor(OrderAggregate.class));
    assertTrue(failure.getMessage().contains("same distance"), failure.getMessage());

  }

  @Test
  @DisplayName("Several runner beans without an attribution end the startup")
  public void severalRunnerBeansEndTheStartup() {

    final var testee = resolver(List.of(), List.of(APPLICATION_RUNNER, passThroughRunner()));

    final var failure = assertThrowsExactly(
        IllegalStateException.class,
        () -> testee.resolveFor(OrderAggregate.class));

    assertTrue(failure.getMessage().contains("TransactionRunnerAware"), failure.getMessage());

  }

  /**
   * An aggregate MongoDB Panache manages - the only case in which the platform knows what
   * the JTA transaction reaches, and therefore the only one it judges.
   */
  private static class ShipmentAggregate {
  }

  /**
   * What an application without the MongoDB client extension has: no probe at all, which is
   * the whole point - nothing on the way to the verdict names a MongoDB type,
   * so a native image of such an application links.
   */
  private static Instance<MongoDeploymentProbe> noProbe() {

    return InstanceDouble.of(List.of());

  }

  private static Instance<MongoDeploymentProbe> probeAnswering(
      final Boolean replicaSet) {

    return InstanceDouble.of(List.of(() -> replicaSet));

  }

  private static QuarkusTransactionRunnerResolver mongoAggregateResolver(
      final Instance<MongoDeploymentProbe> probes) {

    return new QuarkusTransactionRunnerResolver(
        InstanceDouble.of(List.of()), InstanceDouble
            .of(List.of()), InstanceDouble
                .of(
                    List
                        .of(
                            new PanacheMongoActiveRecordAggregatePersistence<>(ShipmentAggregate.class))), probes, PLATFORM_RUNNER);

  }

  @Test
  @DisplayName("A MongoDB deployment which is no replica set is named, with the way out")
  public void mongoDeploymentWithoutReplicaSetIsNamed() {

    final var coverage = mongoAggregateResolver(probeAnswering(Boolean.FALSE))
        .coverageOf(ShipmentAggregate.class);

    assertEquals(TransactionCoverage.Verdict.UNGUARDED, coverage.verdict());
    assertTrue(coverage.message().contains(ShipmentAggregate.class.getName()), coverage.message());
    assertTrue(coverage.message().contains("replica set"), coverage.message());

  }

  @Test
  @DisplayName("On a replica set the MongoDB transaction covers the aggregate")
  public void replicaSetCoversTheAggregate() {

    assertEquals(
        TransactionCoverage.Verdict.COVERED,
        mongoAggregateResolver(probeAnswering(Boolean.TRUE))
            .coverageOf(ShipmentAggregate.class)
            .verdict());

  }

  @Test
  @DisplayName("A question the deployment did not answer does not become a verdict")
  public void unansweredProbeIsNoVerdict() {

    assertEquals(
        TransactionCoverage.Verdict.COVERED,
        mongoAggregateResolver(probeAnswering(null))
            .coverageOf(ShipmentAggregate.class)
            .verdict());

  }

  @Test
  @DisplayName("Without the MongoDB extension nothing is probed, and nothing is claimed")
  public void withoutTheMongoExtensionNothingIsProbed() {

    assertEquals(
        TransactionCoverage.Verdict.COVERED,
        mongoAggregateResolver(noProbe())
            .coverageOf(ShipmentAggregate.class)
            .verdict());

  }

}
