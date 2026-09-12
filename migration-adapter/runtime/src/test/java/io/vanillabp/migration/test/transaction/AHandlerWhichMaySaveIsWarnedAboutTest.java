package io.vanillabp.migration.test.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.transaction.SavingHandlerCheck;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.extension.spi.handler.CoreHandlerParameter;
import io.vanillabp.integration.extension.spi.handler.HandlerContract;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import lombok.Getter;

/**
 * What a boot says about an extension whose handlers may change the workflow aggregate.
 * <p>
 * Such a handler is a second writer on an aggregate which already has one, and where the
 * persistence cannot notice a concurrent change the later write wins without a word. The
 * boot is the last moment at which anybody knows that saving is allowed, so this is where it
 * is said - once per BPMN process, with the page which shows the way out.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AHandlerWhichMaySaveIsWarnedAboutTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String EXTENSION = "sample";

  /**
   * Stands in for <code>jakarta.persistence.Version</code> respectively
   * <code>org.springframework.data.annotation.Version</code>, which the core must not
   * depend on - it recognises them by their name.
   */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({
      ElementType.FIELD, ElementType.METHOD
  })
  public @interface Version {
  }

  /**
   * The annotation the extension brings.
   */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface Note {
  }

  @Getter
  public static class Aggregate {

    String id;

  }

  @Getter
  public static class VersionedAggregate extends Aggregate {

    @Version
    long version;

  }

  /**
   * A workflow service with a method of the extension - the shape which earns the warning.
   */
  public static class NotingService {

    @Note
    public String noteOfTheTask() {

      return "a note";

    }

  }

  /**
   * A second workflow service class serving the same BPMN process, with a method of the
   * extension under a key of its own - the shape which makes the check run twice.
   */
  public static class AlsoNotingService {

    @Note
    public String anotherNoteOfTheTask() {

      return "another note";

    }

  }

  /**
   * A workflow service of the same application without any method of the extension.
   */
  public static class SilentService {

    public String nothingForTheExtension() {

      return "nothing";

    }

  }

  private static class InMemoryPersistence<A extends Aggregate> implements AggregatePersistenceAware<A> {

    private final Class<A> aggregateClass;

    private final Map<Object, A> aggregates = new HashMap<>();

    InMemoryPersistence(
        final Class<A> aggregateClass) {

      this.aggregateClass = aggregateClass;

    }

    @Override
    public Class<A> getAggregateClass() {

      return aggregateClass;

    }

    @Override
    public Object getAggregateId(
        final A aggregate) {

      return aggregate.id;

    }

    @Override
    public Class<?> getAggregateIdType() {

      return String.class;

    }

    @Override
    public A save(
        final A aggregate) {

      aggregates.put(aggregate.id, aggregate);
      return aggregate;

    }

    @Override
    public A loadById(
        final Object aggregateId) {

      return aggregates.get(aggregateId);

    }

  }

  /**
   * The runner is never asked here - no test of this class runs a handler.
   */
  private static class TransactionRunnerStub implements TransactionRunner {

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

  private static MigrationAdapterProperties properties() {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of("test", AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of("test"))
        .build();
    properties.validateAndLink();
    return properties;

  }

  private static HandlerContract noteContract() {

    return HandlerContract
        .of(EXTENSION, Note.class)
        .lookupKeys(annotation -> List.of())
        .coreParameters(CoreHandlerParameter.WORKFLOW_AGGREGATE)
        .deliversReturnValue()
        .build();

  }

  /**
   * What both platforms do while they boot: a workflow service is registered and an
   * extension registers what its annotation means.
   *
   * @return Everything the check wrote while that happened
   */
  private <A extends Aggregate> List<String> whatTheBootSaid(
      final Class<A> workflowAggregateClass,
      final List<Class<?>> workflowServiceClasses) {

    final var registry = new WorkflowTaskRegistry(new TransactionRunnerStub());
    final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logWatcher.start();
    final var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
        .getLogger(SavingHandlerCheck.class);
    logger.addAppender(logWatcher);
    try {
      registry
          .getExtensionHandlers()
          .register(noteContract());
      workflowServiceClasses
          .forEach(workflowServiceClass -> registry
              .registerWorkflowService(
                  MODULE,
                  PROCESS,
                  workflowServiceClass,
                  () -> null,
                  type -> null,
                  processServiceOf(workflowAggregateClass)));
      return logWatcher.list
          .stream()
          .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
          .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    } finally {
      logger.detachAppender(logWatcher);
    }

  }

  private <A extends Aggregate> MigrationProcessService<A> processServiceOf(
      final Class<A> workflowAggregateClass) {

    final var adapter = org.mockito.Mockito.mock(MigratableProcessService.class);
    org.mockito.Mockito
        .lenient()
        .when(adapter.getAdapterId())
        .thenReturn("test");
    return MigrationProcessService
        .forBpmnProcess(MODULE, PROCESS, workflowAggregateClass)
        .properties(properties())
        .aggregatePersistence(new InMemoryPersistence<>(workflowAggregateClass))
        .processServices(List.of(adapter))
        .build();

  }

  @Test
  @DisplayName("An aggregate whose persistence notices nothing is named, with the extension and the way out")
  public void anAggregateWithoutProtectionIsNamed() {

    final var said = whatTheBootSaid(Aggregate.class, List.of(NotingService.class));

    assertEquals(1, said.size(), said.toString());
    final var message = said.getFirst();
    assertTrue(message.contains(EXTENSION), message);
    assertTrue(message.contains("Note"), message);
    assertTrue(message.contains(MODULE), message);
    assertTrue(message.contains(PROCESS), message);
    assertTrue(message.contains(Aggregate.class.getName()), message);
    assertTrue(message.contains("@Version"), message);
    assertTrue(message.contains("Workflow aggregates"), message);
    // what it does NOT promise: no check while running, no second report
    assertTrue(message.contains("neither checks for the conflict nor repeats"), message);

  }

  @Test
  @DisplayName("An aggregate which notices a second writer stays quiet")
  public void aProtectedAggregateStaysQuiet() {

    assertEquals(List.of(), whatTheBootSaid(VersionedAggregate.class, List.of(NotingService.class)));

  }

  @Test
  @DisplayName("A workflow service without a method of the extension is nothing to warn about")
  public void aServiceWithoutSuchMethodsStaysQuiet() {

    assertEquals(List.of(), whatTheBootSaid(Aggregate.class, List.of(SilentService.class)));

  }

  @Test
  @DisplayName("A BPMN process served by two classes of handlers is named once")
  public void theWarningIsGivenOncePerProcess() {

    assertEquals(
        1,
        whatTheBootSaid(Aggregate.class, List.of(NotingService.class, AlsoNotingService.class)).size());

  }

}
