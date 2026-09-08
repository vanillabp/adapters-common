package io.vanillabp.migration.test.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.transaction.ConcurrentTokenCheck;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;
import io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.WorkflowTask;
import lombok.Getter;

/**
 * Two writers on one workflow aggregate in a version the BPMS still HOLDS. An older version
 * with a parallel gateway the newest model dropped keeps forking every workflow which was
 * started before it, and those are the workflows which run longest - so a check reading only
 * the model of this boot never sees the case which lasts.
 * <p>
 * Reading the old model belongs to the adapter
 * ({@link ProcessVersionCatalog#concurrentTokenElementsOfVersion}), deciding what it means
 * belongs to the core, which is the same split the deployed model goes through.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ConcurrentTokensOfHeldVersionsTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String ADAPTER = "c7";

  @Getter
  public static class Aggregate {

    String id;

  }

  /**
   * Stands in for <code>jakarta.persistence.Version</code> respectively
   * <code>org.springframework.data.annotation.Version</code>, which the core must not depend
   * on - it recognizes them by their name.
   */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target({
      java.lang.annotation.ElementType.FIELD, java.lang.annotation.ElementType.METHOD
  })
  public @interface Version {
  }

  /**
   * The same aggregate with the attribute a persistence layer increments per write - a
   * collision raises an exception there instead of being lost, which is what silences the
   * warning.
   */
  @Getter
  public static class VersionedAggregate {

    String id;

    @Version
    long version;

  }

  public static class Service {

    @WorkflowTask(taskDefinition = "approve")
    public void approve(
        final Aggregate aggregate) {
    }

  }

  /**
   * The same service on the aggregate which carries a version attribute.
   */
  public static class VersionedService {

    @WorkflowTask(taskDefinition = "approve")
    public void approve(
        final VersionedAggregate aggregate) {
    }

  }

  /**
   * What the BPMS holds, with every answer handed in by the test.
   */
  private static class CatalogStub implements ProcessVersionCatalog {

    private List<DeployedProcessVersion> versions = List.of();

    private final Map<String, Collection<String>> concurrentTokensPerVersion = new java.util.HashMap<>();

    private final Map<String, Long> instancesPerVersion = new java.util.HashMap<>();

    private boolean canReadModels = true;

    /**
     * Every question this BPMS was asked, in order - what a version nobody runs on costs is
     * exactly what is missing from this list.
     */
    private final List<String> questions = new ArrayList<>();

    @Override
    public List<DeployedProcessVersion> deployedVersionsOf(
        final String workflowModuleId,
        final String bpmnProcessId) {

      return versions;

    }

    @Override
    public DeployedProcessVersion resolveVersion(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String versionOrVersionTag) {

      return versions
          .stream()
          .filter(version -> version.version().equals(versionOrVersionTag))
          .findFirst()
          .orElse(null);

    }

    @Override
    public Collection<BpmnTaskSpec> tasksOfVersion(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version) {

      return List.of(new BpmnTaskSpec("Activity_approve", "approve"));

    }

    @Override
    public Long activeInstanceCountOf(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version) {

      return instancesPerVersion.get(version);

    }

    @Override
    public Collection<String> concurrentTokenElementsOfVersion(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version) {

      questions.add("concurrentTokenElementsOfVersion(%s)".formatted(version));
      return canReadModels
          ? concurrentTokensPerVersion.getOrDefault(version, List.of())
          : null;

    }

  }

  private MigrationAdapterProperties properties;

  private CatalogStub catalog;

  @BeforeEach
  public void setUp() {

    properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("camunda7")));

    catalog = new CatalogStub();
    catalog.versions = List
        .of(DeployedProcessVersion.of("1"), DeployedProcessVersion.of("2"), DeployedProcessVersion.of("3"));
    // the gateway was dropped by the model this boot deployed, and the workflows of the two
    // older versions are still forking on it
    catalog.concurrentTokensPerVersion.put("1", List.of("Gateway_Fork"));
    catalog.concurrentTokensPerVersion.put("2", List.of("Gateway_Fork", "Event_Reminder"));
    catalog.instancesPerVersion.put("1", 7L);
    catalog.instancesPerVersion.put("2", 3L);

  }

  @Test
  @DisplayName("A held version which forks is reported, naming the version and its elements")
  public void aHeldVersionWhichForksIsReported() {

    final var messages = theModuleFinishedDeploying(Aggregate.class);

    assertEquals(1, messages.size(), messages.toString());
    final var message = messages.getFirst();
    assertTrue(message.contains("Version(s) '1', '2'"), message);
    assertTrue(message.contains("the BPMS still holds"), message);
    assertTrue(message.contains("Gateway_Fork"), message);
    assertTrue(message.contains("Event_Reminder"), message);
    assertTrue(message.contains(Aggregate.class.getName()), message);

  }

  @Test
  @DisplayName("A version nobody runs on is not even read")
  public void aVersionNobodyRunsOnIsNotRead() {

    catalog.instancesPerVersion.clear();
    catalog.instancesPerVersion.put("1", 0L);
    catalog.instancesPerVersion.put("2", 0L);

    assertEquals(List.of(), theModuleFinishedDeploying(Aggregate.class));
    assertEquals(
        List.of(),
        catalog.questions,
        "a version no workflow is on was read for a conflict which cannot happen there");

  }

  @Test
  @DisplayName("A BPMS which cannot say how many workflows run is asked anyway")
  public void aBpmsWhichCannotCountIsAskedAnyway() {

    catalog.instancesPerVersion.clear();

    // "cannot tell" is not "nobody", and a lost update is the one outcome worth a model read
    assertFalse(theModuleFinishedDeploying(Aggregate.class).isEmpty());

  }

  @Test
  @DisplayName("An adapter which cannot read a held model says nothing about it")
  public void anAdapterWhichCannotReadModelsIsQuiet() {

    catalog.canReadModels = false;

    assertEquals(List.of(), theModuleFinishedDeploying(Aggregate.class));

  }

  @Test
  @DisplayName("An aggregate with a version attribute stays quiet here as well")
  public void anAggregateWithAVersionAttributeIsQuiet() {

    assertEquals(List.of(), theModuleFinishedDeploying(VersionedAggregate.class));

  }

  @Test
  @DisplayName("The deployed model speaks first, and the held versions add no second warning")
  public void theWarningStaysOnePerBpmnProcess() {

    final var registry = registryServing(Aggregate.class);
    final var messages = loggedByTheCheck(() -> {
      theAdapterWired(registry, List.of("Gateway_Fork"));
      registry.resolveProcessVersions(MODULE);
    });

    assertEquals(1, messages.size(), messages.toString());
    assertTrue(messages.getFirst().contains("The BPMN process '%s'".formatted(PROCESS)), messages.getFirst());

  }

  /**
   * The deployment of a workflow module whose adapter reports no concurrent token of the model
   * it just deployed: the older versions are what the check reads afterwards.
   */
  private List<String> theModuleFinishedDeploying(
      final Class<?> workflowAggregateClass) {

    final var registry = registryServing(workflowAggregateClass);
    return loggedByTheCheck(() -> {
      theAdapterWired(registry, List.of());
      registry.resolveProcessVersions(MODULE);
    });

  }

  private WorkflowTaskRegistry registryServing(
      final Class<?> workflowAggregateClass) {

    final var registry = new WorkflowTaskRegistry(new TransactionRunnerStub(), null, List.of(), properties);
    final var serviceClass = workflowAggregateClass == VersionedAggregate.class
        ? VersionedService.class
        : Service.class;
    registry
        .registerWorkflowService(
            MODULE,
            PROCESS,
            serviceClass,
            () -> null,
            type -> null,
            processService(workflowAggregateClass));
    return registry;

  }

  /**
   * What an adapter does while and after deploying: it wires the model, reports what of it can
   * produce a second token, registers what its BPMS knows and names the version it was given.
   */
  private void theAdapterWired(
      final WorkflowTaskRegistry registry,
      final Collection<String> concurrentTokenElements) {

    registry
        .validateTaskWiring(MODULE, PROCESS, List.of(new BpmnTaskSpec("Activity_approve", "approve")));
    registry.reportConcurrentTokenElements(MODULE, PROCESS, concurrentTokenElements);
    registry.registerProcessVersions(ADAPTER, MODULE, PROCESS, catalog);
    registry.registerDeployedVersion(ADAPTER, MODULE, PROCESS, "3");

  }

  /**
   * The messages the concurrent-token check wrote while the work ran ("normal" logging is
   * switched off during tests).
   */
  private List<String> loggedByTheCheck(
      final Runnable work) {

    final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logWatcher.start();
    final var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
        .getLogger(ConcurrentTokenCheck.class);
    logger.addAppender(logWatcher);
    try {
      work.run();
    } finally {
      logger.detachAndStopAllAppenders();
    }
    return logWatcher.list
        .stream()
        .filter(event -> event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN))
        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
        .toList();

  }

  /**
   * The registry needs a process service to register a workflow service; nothing here invokes
   * it, and its aggregate class is the whole point of the question asked.
   */
  @SuppressWarnings({
      "unchecked", "rawtypes"
  })
  private static io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService<?> processService(
      final Class<?> workflowAggregateClass) {

    final var processService = org.mockito.Mockito
        .mock(io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService.class);
    org.mockito.Mockito
        .when(processService.getWorkflowAggregateClass())
        .thenReturn((Class) workflowAggregateClass);
    return processService;

  }

  /**
   * The transaction runner is irrelevant here - no test in this class runs a handler.
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

}
