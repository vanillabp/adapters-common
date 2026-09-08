package io.vanillabp.migration.test.workflowtask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.workflowtask.ProcessVersions;
import io.vanillabp.integration.adapter.migration.workflowtask.VersionRange;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.version.CachingProcessVersionCatalog;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.BpmsStartTrigger;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;
import io.vanillabp.spi.service.WorkflowTask;
import lombok.Getter;

/**
 * What the <code>version</code> attribute of <code>&#64;WorkflowTask</code>,
 * <code>&#64;WorkflowStartedByBpms</code> and <code>&#64;WorkflowEnded</code> means.
 * <p>
 * Two things are tested here: version ranges pick the method serving a process version
 * (numbers straight away, version tags through the catalog the adapter registered,
 * including the BPMS query for a version deployed by ANOTHER cluster node), and two
 * methods wired to one BPMN element are ambiguous exactly when their ranges overlap.
 * <p>
 * {@link ClassLevelRanges} adds the third source of a range: the
 * <code>&#64;BpmnProcess</code> a method's process was declared with, which the methods
 * naming none of their own serve.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ProcessVersionMatchingTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  /**
   * A second BPMN process of the same workflow aggregate - what
   * <code>secondaryBpmnProcesses</code> declares, each entry with a version of its own.
   */
  private static final String SECONDARY_PROCESS = "CalledProcess";

  private static final String ADAPTER = "test-adapter";

  public static class Aggregate {

    @Getter
    String id;

    String servedBy;

  }

  static class InMemoryPersistence implements AggregatePersistenceAware<Aggregate> {

    final Map<Object, Aggregate> aggregates = new HashMap<>();

    @Override
    public Class<Aggregate> getAggregateClass() {
      return Aggregate.class;
    }

    @Override
    public String getAggregateIdName() {
      return "id";
    }

    @Override
    public Class<?> getAggregateIdType() {
      return String.class;
    }

    @Override
    public Object getAggregateId(
        final Aggregate aggregate) {
      return aggregate.id;
    }

    @Override
    public Aggregate save(
        final Aggregate aggregate) {
      aggregates.put(aggregate.id, aggregate);
      return aggregate;
    }

    @Override
    public Aggregate loadById(
        final Object aggregateId) {
      return aggregates.get(aggregateId);
    }

  }

  static class TransactionRunnerStub implements TransactionRunner {

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

  /**
   * A BPMS answering what it was told to answer, counting how often it was asked - the
   * BPMS query behind {@link CachingProcessVersionCatalog} must not run per task
   * delivery, and it HAS to run once when a version shows up this node never deployed.
   */
  static class RecordingCatalog extends CachingProcessVersionCatalog {

    final List<DeployedProcessVersion> versions = new ArrayList<>();

    int fetches = 0;

    RecordingCatalog() {

      // no floor between two queries: the test wants the on-demand query to happen
      super(Duration.ZERO);

    }

    @Override
    protected List<DeployedProcessVersion> fetchDeployedVersions(
        final String workflowModuleId,
        final String bpmnProcessId) {

      fetches++;
      return List.copyOf(versions);

    }

  }

  private final InMemoryPersistence persistence = new InMemoryPersistence();

  private MigrationProcessService<Aggregate> processService() {

    return processService(PROCESS);

  }

  private MigrationProcessService<Aggregate> processService(
      final String bpmnProcessId) {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of(ADAPTER))
        .build();
    properties.validateAndLink();

    @SuppressWarnings("unchecked")
    final MigratableProcessService<Aggregate> adapter = mock(MigratableProcessService.class);
    lenient().when(adapter.getAdapterId()).thenReturn(ADAPTER);

    return MigrationProcessService
        .forBpmnProcess(MODULE, bpmnProcessId, Aggregate.class)
        .properties(properties)
        .aggregatePersistence(persistence)
        .processServices(List.of(adapter))
        .build();

  }

  private WorkflowTaskRegistry registry(
      final Class<?> workflowServiceClass,
      final Supplier<Object> bean) {

    final var registry = new WorkflowTaskRegistry(new TransactionRunnerStub());
    registry
        .registerWorkflowService(MODULE, PROCESS, workflowServiceClass, bean, type -> null, processService());
    return registry;

  }

  /**
   * Two workflow service classes of ONE BPMN process - the shape a class-level version
   * range exists for.
   */
  private WorkflowTaskRegistry registryOf(
      final Class<?> oneClass,
      final Supplier<Object> oneBean,
      final Class<?> otherClass,
      final Supplier<Object> otherBean) {

    final var registry = new WorkflowTaskRegistry(new TransactionRunnerStub());
    final var processService = processService();
    registry.registerWorkflowService(MODULE, PROCESS, oneClass, oneBean, type -> null, processService);
    registry.registerWorkflowService(MODULE, PROCESS, otherClass, otherBean, type -> null, processService);
    return registry;

  }

  private Aggregate storeAggregate(
      final String id) {

    final var aggregate = new Aggregate();
    aggregate.id = id;
    persistence.aggregates.put(id, aggregate);
    return aggregate;

  }

  /**
   * The messages a logger emitted while the given work ran - the guiding messages are
   * WARNings, and "normal" logging is switched off during tests.
   */
  private List<String> loggedBy(
      final Class<?> loggingClass,
      final Runnable work) {

    final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logWatcher.start();
    final var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(loggingClass);
    logger.addAppender(logWatcher);
    try {
      work.run();
    } finally {
      logger.detachAndStopAllAppenders();
    }
    return logWatcher.list
        .stream()
        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
        .toList();

  }

  private TaskInvocationContext taskContext(
      final String aggregateId,
      final String processVersion) {

    return new TaskInvocationContext() {

      @Override
      public String getTaskDefinition() {
        return "task";
      }

      @Override
      public String getWorkflowAggregateId() {
        return aggregateId;
      }

      @Override
      public String getProcessVersion() {
        return processVersion;
      }

    };

  }

  @Nested
  @DisplayName("Overlapping version ranges")
  class Overlaps {

    private boolean overlaps(
        final String one,
        final String other) {

      return VersionRange
          .parse(one, "test")
          .overlaps(VersionRange.parse(other, "test"));

    }

    @Test
    @DisplayName("Ranges made of numbers are placed without asking a BPMS")
    public void numericRanges() {

      assertTrue(overlaps("*", "3"), "'*' covers every version");
      assertTrue(overlaps("2", "1-3"), "'2' sits inside '1-3'");
      assertTrue(overlaps("1-3", "2"), "and the other way round");
      assertTrue(overlaps("1-3", "3-5"), "the boundaries are included");
      assertTrue(overlaps("1-3", "<4"), "'<4' covers 1 to 3");
      assertTrue(overlaps(">2", ">5"), "both are open ended upwards");
      assertTrue(overlaps("2", "2"), "the same specification twice");

      assertFalse(overlaps("1-2", ">2"), "disjoint - serving two versions of a process");
      assertFalse(overlaps("<2", "2"), "'<2' excludes its boundary");
      assertFalse(overlaps(">2", "2"), "'>2' excludes its boundary");
      assertFalse(overlaps("1-2", "3-4"), "next to each other, not overlapping");
      assertFalse(overlaps("<2", ">2"), "everything but version 2");

      // version 1's README documented '>=' and '<=' although nothing implemented them
      assertTrue(overlaps(">=2", "2"), "'>=2' includes its boundary");
      assertTrue(overlaps("<=2", "2"), "'<=2' includes its boundary");
      assertFalse(overlaps("<=1", ">=2"), "adjacent, both inclusive");

    }

    @Test
    @DisplayName("Without a BPMS only identical version tags are known to overlap")
    public void tagsWithoutCatalog() {

      assertTrue(overlaps("release-2024", "release-2024"), "written identically");
      assertTrue(overlaps("*", "release-2024"), "'*' covers every version, tagged or not");
      assertFalse(overlaps("release-2024", "release-2025"), "not comparable without a BPMS");
      assertFalse(overlaps(">release-2024", "1-3"), "not comparable without a BPMS");

    }

    @Test
    @DisplayName("Once the BPMS placed the tags, ranges naming them are compared like numbers")
    public void tagsWithCatalog() {

      final var catalog = new RecordingCatalog();
      catalog.versions.add(DeployedProcessVersion.of("1", "v1.0"));
      catalog.versions.add(DeployedProcessVersion.of("2", "v2.0"));
      catalog.versions.add(DeployedProcessVersion.of("3", "v3.0"));
      final VersionRange.ProcessVersionResolver resolver = versionOrTag -> catalog
          .resolveVersion(MODULE, PROCESS, versionOrTag);

      assertTrue(
          VersionRange.parse("v1.0..v2.0", "test").overlaps(VersionRange.parse("2", "test"), resolver),
          "version 2 carries tag 'v2.0'");
      assertFalse(
          VersionRange.parse("v1.0..v2.0", "test").overlaps(VersionRange.parse(">v2.0", "test"), resolver),
          "the tagged range ends where the other one starts");
      assertTrue(
          VersionRange.parse(">v1.0", "test").overlaps(VersionRange.parse("2-3", "test"), resolver),
          "'>v1.0' means versions 2 and up");
      assertFalse(
          VersionRange.parse("v1.0", "test").overlaps(VersionRange.parse("v2.0", "test"), resolver),
          "two different tags of two different versions");

    }

  }

  static class TwoMethodsOneTaskService {

    @WorkflowTask(taskDefinition = "task", version = "1-3")
    public void old(
        final Aggregate aggregate) {
    }

    @WorkflowTask(taskDefinition = "task", version = "2")
    public void newer(
        final Aggregate aggregate) {
    }

  }

  static class DisjointVersionsService {

    @WorkflowTask(taskDefinition = "task", version = "1-2")
    public void upToTwo(
        final Aggregate aggregate) {

      aggregate.servedBy = "upToTwo";

    }

    @WorkflowTask(taskDefinition = "task", version = ">2")
    public void afterTwo(
        final Aggregate aggregate) {

      aggregate.servedBy = "afterTwo";

    }

  }

  static class UnversionedService {

    @WorkflowTask(taskDefinition = "task")
    public void everyVersion(
        final Aggregate aggregate) {

      aggregate.servedBy = "everyVersion";

    }

  }

  static class TaggedVersionsService {

    @WorkflowTask(taskDefinition = "task", version = "release-2024")
    public void tagged(
        final Aggregate aggregate) {

      aggregate.servedBy = "tagged";

    }

    @WorkflowTask(taskDefinition = "task", version = ">release-2024")
    public void afterTheTag(
        final Aggregate aggregate) {

      aggregate.servedBy = "afterTheTag";

    }

  }

  static class OverlappingTagsService {

    @WorkflowTask(taskDefinition = "task", version = "v1.0..v3.0")
    public void wide(
        final Aggregate aggregate) {
    }

    @WorkflowTask(taskDefinition = "task", version = "v2.0")
    public void narrow(
        final Aggregate aggregate) {
    }

  }

  static class UnknownTagService {

    @WorkflowTask(taskDefinition = "task", version = "does-not-exist")
    public void nobody(
        final Aggregate aggregate) {
    }

  }

  @Test
  @DisplayName("Two methods of ONE class wired to one task definition fail if their ranges overlap")
  public void twoMethodsOfOneClassAreCompared() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> registry(TwoMethodsOneTaskService.class, TwoMethodsOneTaskService::new));

    assertTrue(exception.getMessage().contains("old"));
    assertTrue(exception.getMessage().contains("newer"));
    assertTrue(exception.getMessage().contains("version"));

  }

  @Test
  @DisplayName("An inclusive boundary is honored, as version 1's documentation promised")
  public void inclusiveBoundaries() {

    assertTrue(VersionRange.parse(">=2", "test").matches("2"));
    assertTrue(VersionRange.parse(">=2", "test").matches("3"));
    assertFalse(VersionRange.parse(">=2", "test").matches("1"));
    assertTrue(VersionRange.parse("<=2", "test").matches("2"));
    assertFalse(VersionRange.parse("<=2", "test").matches("3"));

  }

  @Test
  @DisplayName("Disjoint ranges register and the version decides which method runs")
  public void disjointRangesServeTheirVersions() {

    final var testee = registry(DisjointVersionsService.class, DisjointVersionsService::new);
    storeAggregate("4711");

    testee.invokeWorkflowTask(MODULE, PROCESS, taskContext("4711", "2"));
    assertEquals("upToTwo", persistence.aggregates.get("4711").servedBy);

    testee.invokeWorkflowTask(MODULE, PROCESS, taskContext("4711", "3"));
    assertEquals("afterTwo", persistence.aggregates.get("4711").servedBy);

    // a BPMS reporting NO version is served by '*' only: whether a version nobody
    // reported lies within '1-2' cannot be answered, and the answer must not depend on
    // the order the methods happen to be reflected in
    final var unreported = assertThrows(
        IllegalStateException.class,
        () -> testee.invokeWorkflowTask(MODULE, PROCESS, taskContext("4711", null)));
    assertTrue(unreported.getMessage().contains("reports no process version"), unreported.getMessage());
    assertEquals("afterTwo", persistence.aggregates.get("4711").servedBy, "no method ran");

  }

  @Test
  @DisplayName("A BPMS reporting no version is served by the method without a version")
  public void withoutAReportedVersionTheUnrestrictedMethodServes() {

    final var testee = registry(UnversionedService.class, UnversionedService::new);
    storeAggregate("4711");

    testee.invokeWorkflowTask(MODULE, PROCESS, taskContext("4711", null));
    assertEquals("everyVersion", persistence.aggregates.get("4711").servedBy);

    // the same method serves a reported version, which is what makes the attribute
    // free of cost for an application not using it
    testee.invokeWorkflowTask(MODULE, PROCESS, taskContext("4711", "7"));
    assertEquals("everyVersion", persistence.aggregates.get("4711").servedBy);

  }

  @Test
  @DisplayName("A version tag names the version carrying it, resolved through the adapter's catalog")
  public void versionTagsAreResolvedByTheBpms() {

    final var testee = registry(TaggedVersionsService.class, TaggedVersionsService::new);
    final var catalog = new RecordingCatalog();
    catalog.versions.add(DeployedProcessVersion.of("1", null));
    catalog.versions.add(DeployedProcessVersion.of("2", "release-2024"));
    testee.registerProcessVersions(ADAPTER, MODULE, PROCESS, catalog);
    testee.resolveProcessVersions(MODULE);
    storeAggregate("4711");

    // the startup resolution asked the BPMS - a task delivery does not ask again
    assertEquals(1, catalog.fetches);

    testee.invokeWorkflowTask(MODULE, PROCESS, taskContext("4711", "2"));
    assertEquals("tagged", persistence.aggregates.get("4711").servedBy);
    assertEquals(1, catalog.fetches);

    // ANOTHER cluster node deployed version 3 while this one is running: the version
    // is unknown here, so the BPMS is asked on demand
    catalog.versions.add(DeployedProcessVersion.of("3", "release-2025"));
    testee.invokeWorkflowTask(MODULE, PROCESS, taskContext("4711", "3"));
    assertEquals("afterTheTag", persistence.aggregates.get("4711").servedBy);
    assertTrue(catalog.fetches > 1, "the version deployed elsewhere was looked up on demand");

  }

  @Test
  @DisplayName("Ranges naming a tag are checked for overlaps once the BPMS placed them")
  public void overlappingTagRangesFailAfterTheDeployment() {

    // registering cannot decide it: no BPMS was asked at that point
    final var testee = registry(OverlappingTagsService.class, OverlappingTagsService::new);
    final var catalog = new RecordingCatalog();
    catalog.versions.add(DeployedProcessVersion.of("1", "v1.0"));
    catalog.versions.add(DeployedProcessVersion.of("2", "v2.0"));
    catalog.versions.add(DeployedProcessVersion.of("3", "v3.0"));
    testee.registerProcessVersions(ADAPTER, MODULE, PROCESS, catalog);

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> testee.resolveProcessVersions(MODULE));

    assertTrue(exception.getMessage().contains("wide"));
    assertTrue(exception.getMessage().contains("narrow"));

  }

  @Test
  @DisplayName("A version tag no BPMS knows is reported at startup and serves nothing")
  public void unknownVersionTagIsReported() {

    final var testee = registry(UnknownTagService.class, UnknownTagService::new);
    final var catalog = new RecordingCatalog();
    catalog.versions.add(DeployedProcessVersion.of("1", "v1.0"));
    testee.registerProcessVersions(ADAPTER, MODULE, PROCESS, catalog);
    final var messages = loggedBy(ProcessVersions.class, () -> testee.resolveProcessVersions(MODULE));
    storeAggregate("4711");

    assertTrue(
        messages.stream().anyMatch(message -> message.contains("does-not-exist")),
        messages.toString());
    assertTrue(
        messages.stream().anyMatch(message -> message.contains("versionTag")),
        "the message names where a tag comes from");

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> testee.invokeWorkflowTask(MODULE, PROCESS, taskContext("4711", "1")));
    assertTrue(exception.getMessage().contains("process version '1'"));

  }

  static class TwoStartsOneEventService {

    @WorkflowStartedByBpms(version = "1-3")
    public void old(
        final Aggregate aggregate) {
    }

    @WorkflowStartedByBpms(version = "2")
    public void newer(
        final Aggregate aggregate) {
    }

  }

  static class VersionedStartsService {

    @WorkflowStartedByBpms(version = "1-2")
    public void upToTwo(
        final Aggregate aggregate) {

      aggregate.servedBy = "upToTwo";

    }

    @WorkflowStartedByBpms(version = ">2")
    public void afterTwo(
        final Aggregate aggregate) {

      aggregate.servedBy = "afterTwo";

    }

  }

  static class TwoEndedOneEventService {

    @WorkflowEnded(version = "1-3")
    public void old(
        final Aggregate aggregate) {
    }

    @WorkflowEnded(version = "2")
    public void newer(
        final Aggregate aggregate) {
    }

  }

  static class VersionedEndedService {

    @WorkflowEnded(version = "1-2")
    public void upToTwo(
        final Aggregate aggregate) {

      aggregate.servedBy = "upToTwo";

    }

    @WorkflowEnded(version = ">2")
    public void afterTwo(
        final Aggregate aggregate) {

      aggregate.servedBy = "afterTwo";

    }

  }

  private BpmsInitiatedStartContext startContext(
      final String naturalIdentity,
      final String processVersion) {

    return new BpmsInitiatedStartContext() {

      @Override
      public String getStartEventId() {
        return "StartEvent_Timer";
      }

      @Override
      public BpmsStartTrigger.Kind getKind() {
        return BpmsStartTrigger.Kind.TIMER;
      }

      @Override
      public Instant getStartInstant() {
        return Instant.parse("2026-08-13T09:15:00Z");
      }

      @Override
      public String getNaturalIdentity() {
        return naturalIdentity;
      }

      @Override
      public String getProcessVersion() {
        return processVersion;
      }

    };

  }

  private WorkflowEndedContext endedContext(
      final String processVersion) {

    return new WorkflowEndedContext() {

      @Override
      public String getWorkflowAggregateId() {
        return "4711";
      }

      @Override
      public Instant getEndTime() {
        return Instant.parse("2026-08-13T09:15:00Z");
      }

      @Override
      public io.vanillabp.spi.service.WorkflowEnd.Kind getKind() {
        return io.vanillabp.spi.service.WorkflowEnd.Kind.COMPLETED;
      }

      @Override
      public String getProcessVersion() {
        return processVersion;
      }

    };

  }

  @Test
  @DisplayName("The same rules hold for @WorkflowStartedByBpms")
  public void bpmsInitiatedStartsAreVersioned() {

    assertThrows(
        IllegalStateException.class,
        () -> registry(TwoStartsOneEventService.class, TwoStartsOneEventService::new),
        "overlapping ranges of two methods of one class");

    final var testee = registry(VersionedStartsService.class, VersionedStartsService::new);

    testee.startWorkflowByBpms(MODULE, PROCESS, startContext("4711", "2"));
    assertEquals("upToTwo", persistence.aggregates.get("4711").servedBy);

    // another workflow: a start reporting an identity known already reuses its
    // aggregate and calls no method at all
    testee.startWorkflowByBpms(MODULE, PROCESS, startContext("4712", "3"));
    assertEquals("afterTwo", persistence.aggregates.get("4712").servedBy);

    // without a reported version no ranged method runs: the aggregate is built anyway,
    // since the BPMS created that workflow either way - and it is said out loud
    final var messages = loggedBy(
        io.vanillabp.integration.adapter.migration.workflowstart.BpmsInitiatedStarts.class,
        () -> testee.startWorkflowByBpms(MODULE, PROCESS, startContext("4713", null)));
    assertNull(persistence.aggregates.get("4713").servedBy, "no method initialized the aggregate");
    assertTrue(
        messages.stream().anyMatch(message -> message.contains("reports no process version")),
        messages.toString());

  }

  @Test
  @DisplayName("The same rules hold for @WorkflowEnded")
  public void workflowEndedIsVersioned() {

    assertThrows(
        IllegalStateException.class,
        () -> registry(TwoEndedOneEventService.class, TwoEndedOneEventService::new),
        "overlapping ranges of two methods of one class");

    final var testee = registry(VersionedEndedService.class, VersionedEndedService::new);
    storeAggregate("4711");

    testee.workflowEnded(MODULE, PROCESS, endedContext("2"));
    assertEquals("upToTwo", persistence.aggregates.get("4711").servedBy);

    testee.workflowEnded(MODULE, PROCESS, endedContext("3"));
    assertEquals("afterTwo", persistence.aggregates.get("4711").servedBy);

    // a notification without a version reaches no ranged method - a warning, because
    // the application asked to hear about the end and does not
    final var messages = loggedBy(
        io.vanillabp.integration.adapter.migration.workflowend.WorkflowEndedHandlers.class,
        () -> testee.workflowEnded(MODULE, PROCESS, endedContext(null)));
    assertEquals("afterTwo", persistence.aggregates.get("4711").servedBy, "no method ran");
    assertTrue(
        messages.stream().anyMatch(message -> message.contains("reports no process version")),
        messages.toString());

  }

  @Test
  @DisplayName("Without any catalog a version tag is reported once, naming what a BPMS would have to do")
  public void withoutCatalogTagsAreReportedOnce() {

    final var testee = registry(UnknownTagService.class, UnknownTagService::new);
    storeAggregate("4711");

    final var messages = loggedBy(ProcessVersions.class, () -> {
      assertThrows(
          IllegalStateException.class,
          () -> testee.invokeWorkflowTask(MODULE, PROCESS, taskContext("4711", "1")));
      assertThrows(
          IllegalStateException.class,
          () -> testee.invokeWorkflowTask(MODULE, PROCESS, taskContext("4711", "1")));
    });

    assertEquals(
        1,
        messages
            .stream()
            .filter(message -> message.contains("no adapter of this application can be asked"))
            .count(),
        "the same unknown version is reported once, not per task");

  }

  @Test
  @DisplayName("Deploy results feed the catalog, so the version deployed now needs no query")
  public void recordedVersionsAvoidQueries() {

    final var catalog = new RecordingCatalog();
    catalog.record(MODULE, PROCESS, DeployedProcessVersion.of("7", "release-2026"));

    final var resolved = catalog.resolveVersion(MODULE, PROCESS, "release-2026");
    assertEquals("7", resolved.version());
    assertEquals(0, catalog.fetches, "what the deploy command reported is known already");

    // an unknown version is looked up, and what was recorded survives the lookup
    catalog.versions.add(DeployedProcessVersion.of("6", "release-2025"));
    assertEquals("6", catalog.resolveVersion(MODULE, PROCESS, "6").version());
    assertEquals(1, catalog.fetches);
    assertEquals("7", catalog.resolveVersion(MODULE, PROCESS, "release-2026").version());

  }

  @Test
  @DisplayName("A version never seen is looked up at once, a value which stays unknown only once per interval")
  public void theQueryFloorIsPerValue() {

    final var queries = new java.util.concurrent.atomic.AtomicInteger();
    final var versions = new ArrayList<DeployedProcessVersion>();
    versions.add(DeployedProcessVersion.of("1", "v1.0"));
    final var catalog = new CachingProcessVersionCatalog(Duration.ofHours(1)) {

      @Override
      protected List<DeployedProcessVersion> fetchDeployedVersions(
          final String workflowModuleId,
          final String bpmnProcessId) {

        queries.incrementAndGet();
        return List.copyOf(versions);

      }

    };

    assertEquals("1", catalog.resolveVersion(MODULE, PROCESS, "v1.0").version());
    assertEquals(1, queries.get());

    // a tag which does not exist: asked once, then held back by the interval
    assertNull(catalog.resolveVersion(MODULE, PROCESS, "does-not-exist"));
    assertNull(catalog.resolveVersion(MODULE, PROCESS, "does-not-exist"));
    assertEquals(2, queries.get());

    // a version another node deployed meanwhile: looked up right away, although the
    // interval of the unknown tag has not passed
    versions.add(DeployedProcessVersion.of("2", "v2.0"));
    assertEquals("2", catalog.resolveVersion(MODULE, PROCESS, "2").version());
    assertEquals(3, queries.get());

  }

  @Test
  @DisplayName("A BPMS query failing leaves the version unknown instead of failing the task")
  public void failingQueryIsSurvived() {

    final var catalog = new CachingProcessVersionCatalog(Duration.ZERO) {

      @Override
      protected List<DeployedProcessVersion> fetchDeployedVersions(
          final String workflowModuleId,
          final String bpmnProcessId) {

        throw new IllegalStateException("BPMS not reachable");

      }

    };

    final var messages = loggedBy(CachingProcessVersionCatalog.class, () -> {
      assertEquals(List.of(), catalog.deployedVersionsOf(MODULE, PROCESS));
      assertNull(catalog.resolveVersion(MODULE, PROCESS, "v1.0"));
    });

    assertTrue(
        messages.stream().anyMatch(message -> message.contains("Could not determine the deployed versions")),
        messages.toString());

  }

  @Test
  @DisplayName("Versions ordered by deployment time serve ranges of a BPMS not counting upwards")
  public void deploymentTimeOrdersNonNumericVersions() {

    final var catalog = new RecordingCatalog();
    catalog.versions
        .add(new DeployedProcessVersion("a4c1", "v1.0", Instant.parse("2026-01-01T00:00:00Z")));
    catalog.versions
        .add(new DeployedProcessVersion("b7f2", "v2.0", Instant.parse("2026-06-01T00:00:00Z")));
    final VersionRange.ProcessVersionResolver resolver = versionOrTag -> catalog
        .resolveVersion(MODULE, PROCESS, versionOrTag);

    assertTrue(VersionRange.parse(">v1.0", "test").matches("b7f2", resolver));
    assertFalse(VersionRange.parse(">v2.0", "test").matches("b7f2", resolver));
    assertTrue(VersionRange.parse("v1.0..v2.0", "test").matches("a4c1", resolver));

  }

  /**
   * <code>&#64;BpmnProcess(version = ...)</code> as the fallback of the three method
   * annotations: one workflow service class per generation of a model, with no method
   * repeating the range.
   */
  @Nested
  @DisplayName("A range declared by @BpmnProcess")
  class ClassLevelRanges {

    @WorkflowService(
        workflowAggregateClass = Aggregate.class,
        bpmnProcess = @BpmnProcess(bpmnProcessId = PROCESS, version = "1-2"))
    class UpToTwoService {

      @WorkflowTask(taskDefinition = "task")
      public void served(
          final Aggregate aggregate) {

        aggregate.servedBy = "upToTwo";

      }

    }

    @WorkflowService(
        workflowAggregateClass = Aggregate.class,
        bpmnProcess = @BpmnProcess(bpmnProcessId = PROCESS, version = ">2"))
    class AfterTwoService {

      @WorkflowTask(taskDefinition = "task")
      public void served(
          final Aggregate aggregate) {

        aggregate.servedBy = "afterTwo";

      }

    }

    @WorkflowService(
        workflowAggregateClass = Aggregate.class,
        bpmnProcess = @BpmnProcess(bpmnProcessId = PROCESS, version = "1-3"))
    class OneToThreeService {

      @WorkflowTask(taskDefinition = "task")
      public void served(
          final Aggregate aggregate) {
      }

    }

    @WorkflowService(
        workflowAggregateClass = Aggregate.class,
        bpmnProcess = @BpmnProcess(bpmnProcessId = PROCESS, version = "2-4"))
    class TwoToFourService {

      @WorkflowTask(taskDefinition = "task")
      public void served(
          final Aggregate aggregate) {
      }

    }

    @WorkflowService(
        workflowAggregateClass = Aggregate.class,
        bpmnProcess = @BpmnProcess(bpmnProcessId = PROCESS, version = "<10"))
    class MethodWinsService {

      @WorkflowTask(taskDefinition = "task", version = "12")
      public void ownRange(
          final Aggregate aggregate) {

        aggregate.servedBy = "ownRange";

      }

    }

    @WorkflowService(
        workflowAggregateClass = Aggregate.class,
        bpmnProcess = @BpmnProcess(bpmnProcessId = PROCESS))
    class EveryVersionService {

      @WorkflowTask(taskDefinition = "task")
      public void served(
          final Aggregate aggregate) {

        aggregate.servedBy = "everyVersion";

      }

    }

    @WorkflowService(
        workflowAggregateClass = Aggregate.class,
        bpmnProcess = @BpmnProcess(bpmnProcessId = PROCESS, version = "1-2"),
        secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = SECONDARY_PROCESS, version = ">2"))
    class TwoProcessesService {

      @WorkflowTask(taskDefinition = "task")
      public void served(
          final Aggregate aggregate) {

        aggregate.servedBy = "served";

      }

    }

    @WorkflowService(
        workflowAggregateClass = Aggregate.class,
        bpmnProcess = @BpmnProcess(bpmnProcessId = PROCESS, version = "1-2"))
    class StartedAndEndedService {

      @WorkflowStartedByBpms
      public void started(
          final Aggregate aggregate) {

        aggregate.servedBy = "started";

      }

      @WorkflowEnded
      public void ended(
          final Aggregate aggregate) {

        aggregate.servedBy = "ended";

      }

    }

    @Test
    @DisplayName("Two classes with disjoint ranges both register, and the version picks the class")
    public void oneClassPerGenerationOfTheModel() {

      final var testee = registryOf(
          UpToTwoService.class, UpToTwoService::new, AfterTwoService.class, AfterTwoService::new);
      storeAggregate("4711");

      testee.invokeWorkflowTask(MODULE, PROCESS, taskContext("4711", "2"));
      assertEquals("upToTwo", persistence.aggregates.get("4711").servedBy);

      testee.invokeWorkflowTask(MODULE, PROCESS, taskContext("4711", "3"));
      assertEquals("afterTwo", persistence.aggregates.get("4711").servedBy);

    }

    @Test
    @DisplayName("A method which inherits a range is restricted, so a delivery without a version finds none")
    public void anInheritedRangeRestrictsTheMethod() {

      final var testee = registryOf(
          UpToTwoService.class, UpToTwoService::new, AfterTwoService.class, AfterTwoService::new);
      storeAggregate("4711");

      final var unreported = assertThrows(
          IllegalStateException.class,
          () -> testee.invokeWorkflowTask(MODULE, PROCESS, taskContext("4711", null)));

      assertTrue(unreported.getMessage().contains("reports no process version"), unreported.getMessage());
      // neither method carries an attribute, so the message has to say where the
      // range it complains about comes from
      assertTrue(
          unreported.getMessage().contains("inherits its range from the @BpmnProcess"),
          unreported.getMessage());
      assertNull(persistence.aggregates.get("4711").servedBy, "no method ran");

    }

    @Test
    @DisplayName("A method naming its own range keeps it word by word - the class does not narrow it")
    public void theMethodsOwnRangeWins() {

      final var testee = registry(MethodWinsService.class, MethodWinsService::new);
      storeAggregate("4711");

      // '12' lies outside the class' '<10' and is served nevertheless: an intersection
      // would make the attribute in front of the method mean something else
      testee.invokeWorkflowTask(MODULE, PROCESS, taskContext("4711", "12"));
      assertEquals("ownRange", persistence.aggregates.get("4711").servedBy);

      final var covered = assertThrows(
          IllegalStateException.class,
          () -> testee.invokeWorkflowTask(MODULE, PROCESS, taskContext("4711", "5")));
      assertTrue(covered.getMessage().contains("process version '5'"), covered.getMessage());

    }

    @Test
    @DisplayName("Overlapping class ranges end the start, and the message names both origins")
    public void overlappingClassRangesAreRejected() {

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> registryOf(
              OneToThreeService.class,
              OneToThreeService::new,
              TwoToFourService.class,
              TwoToFourService::new));

      assertTrue(exception.getMessage().contains("OneToThreeService"), exception.getMessage());
      assertTrue(exception.getMessage().contains("TwoToFourService"), exception.getMessage());
      assertTrue(exception.getMessage().contains("'1-3', inherited from"), exception.getMessage());
      assertTrue(exception.getMessage().contains("'2-4', inherited from"), exception.getMessage());
      assertTrue(
          exception.getMessage().contains("@BpmnProcess(version = ...)"),
          "the message offers the class attribute as the way to distinguish them");

    }

    @Test
    @DisplayName("A class naming no version leaves its methods serving every version")
    public void withoutAClassRangeNothingChanges() {

      final var testee = registry(EveryVersionService.class, EveryVersionService::new);
      storeAggregate("4711");

      testee.invokeWorkflowTask(MODULE, PROCESS, taskContext("4711", null));
      assertEquals("everyVersion", persistence.aggregates.get("4711").servedBy);

      testee.invokeWorkflowTask(MODULE, PROCESS, taskContext("4711", "7"));
      assertEquals("everyVersion", persistence.aggregates.get("4711").servedBy);

    }

    @Test
    @DisplayName("Each declared process contributes its own range, secondary processes included")
    public void aSecondaryProcessCarriesItsOwnRange() {

      final var testee = new WorkflowTaskRegistry(new TransactionRunnerStub());
      testee
          .registerWorkflowService(
              MODULE, PROCESS, TwoProcessesService.class, TwoProcessesService::new, type -> null, processService());
      testee
          .registerWorkflowService(
              MODULE,
              SECONDARY_PROCESS,
              TwoProcessesService.class,
              TwoProcessesService::new,
              type -> null,
              processService(SECONDARY_PROCESS));
      storeAggregate("4711");

      testee.invokeWorkflowTask(MODULE, PROCESS, taskContext("4711", "2"));
      assertEquals("served", persistence.aggregates.get("4711").servedBy);

      testee.invokeWorkflowTask(MODULE, SECONDARY_PROCESS, taskContext("4711", "3"));
      assertEquals("served", persistence.aggregates.get("4711").servedBy);

      // the same method, restricted differently per process: version 3 of the primary
      // process lies outside the range that process was declared with
      final var outside = assertThrows(
          IllegalStateException.class,
          () -> testee.invokeWorkflowTask(MODULE, PROCESS, taskContext("4711", "3")));
      assertTrue(outside.getMessage().contains("process version '3'"), outside.getMessage());

    }

    @Test
    @DisplayName("@WorkflowStartedByBpms and @WorkflowEnded inherit the same range")
    public void theOtherTwoAnnotationsInheritToo() {

      final var testee = registry(StartedAndEndedService.class, StartedAndEndedService::new);

      testee.startWorkflowByBpms(MODULE, PROCESS, startContext("4711", "2"));
      assertEquals("started", persistence.aggregates.get("4711").servedBy);

      testee.workflowEnded(MODULE, PROCESS, endedContext("2"));
      assertEquals("ended", persistence.aggregates.get("4711").servedBy);

      // version 3 lies outside the class' range: the start builds the aggregate
      // without initialization and the end reaches nobody
      testee.startWorkflowByBpms(MODULE, PROCESS, startContext("4712", "3"));
      assertNull(persistence.aggregates.get("4712").servedBy, "no method initialized the aggregate");

    }

  }

}
