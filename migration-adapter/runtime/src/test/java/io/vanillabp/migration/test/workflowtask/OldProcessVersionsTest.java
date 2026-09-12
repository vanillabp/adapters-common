package io.vanillabp.migration.test.workflowtask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import ch.qos.logback.classic.Level;
import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.OutfadedVersionsInUsePolicy;
import io.vanillabp.integration.adapter.migration.workflowtask.DeployedProcessVersionsCheck;
import io.vanillabp.integration.adapter.migration.workflowtask.OutfadedProcessVersions;
import io.vanillabp.integration.adapter.migration.workflowtask.ProcessVersions;
import io.vanillabp.integration.adapter.migration.workflowtask.VersionRange;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.WorkflowTask;
import lombok.Getter;

/**
 * The startup check for old process versions: does this application still serve the OLDER versions
 * of its processes the BPMS holds, and what does outfading a version do.
 * <p>
 * The BPMS is a stub here, because the question the core answers is version arithmetic,
 * not model reading: the adapters prove the reading half against real engines.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OldProcessVersionsTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String ADAPTER = "c7";

  @Getter
  public static class Aggregate {

    String id;

  }

  /**
   * Serves the deployed version 3 and, with a second method, the older version 2. The
   * task 'goneTask' existed in older models only and is served by nobody.
   */
  public static class Service {

    @WorkflowTask(taskDefinition = "stillThere")
    public void stillThere(
        final Aggregate aggregate) {
    }

    @WorkflowTask(taskDefinition = "oldOnly", version = "2")
    public void oldOnly(
        final Aggregate aggregate) {
    }

  }

  /**
   * A method for a version this BPMS does not hold - a typo, a version deleted from
   * the engine, or code shipped before the model it needs.
   */
  public static class DeadVersionService {

    @WorkflowTask(taskDefinition = "stillThere", version = "99")
    public void neverRuns(
        final Aggregate aggregate) {
    }

  }

  /**
   * What the BPMS is asked, with every answer handed in by the test.
   */
  private static class CatalogStub implements DeployedProcessVersionsCheck.ProcessVersionCatalogAccess {

    private List<DeployedProcessVersion> versions = List.of();

    private Map<String, Collection<BpmnTaskSpec>> tasksPerVersion = Map.of();

    private Map<String, Long> instancesPerVersion = Map.of();

    private boolean canReadModels = true;

    private boolean canCountInstances = true;

    @Override
    public List<DeployedProcessVersion> deployedVersionsOf(
        final String workflowModuleId,
        final String bpmnProcessId) {

      questions.merge("deployedVersionsOf", 1, Integer::sum);
      return versions;

    }

    @Override
    public Collection<BpmnTaskSpec> tasksOfVersion(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version) {

      questions.merge("tasksOfVersion(%s)".formatted(version), 1, Integer::sum);
      return canReadModels
          ? tasksPerVersion.getOrDefault(version, List.of())
          : null;

    }

    /**
     * Every question this BPMS was asked, by name. What a start asks may depend on the
     * versions a BPMS holds; it must not depend on how many workflows ran through them,
     * which is decision 19 in the repository's DECISIONS.md and what
     * {@link OldProcessVersionsTest#theQuestionsDoNotDependOnHowManyWorkflowsRun()}
     * holds.
     */
    private final Map<String, Integer> questions = new java.util.HashMap<>();

    @Override
    public Long activeInstanceCountOf(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version) {

      instanceCountQueries.merge(version, 1, Integer::sum);
      questions.merge("activeInstanceCountOf(%s)".formatted(version), 1, Integer::sum);
      return canCountInstances
          ? instancesPerVersion.getOrDefault(version, 0L)
          : null;

    }

    /**
     * How often the BPMS was asked for the instance count of each version. Asking is a
     * QUERY, and the number of versions a BPMS holds grows with every deployment which
     * changes a model, so an extra ask per version is a cost which grows with the age of
     * the application.
     */
    private final Map<String, Integer> instanceCountQueries = new java.util.HashMap<>();

    private Map<String, Collection<io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier>> identifiersPerVersion = Map
        .of();

    @Override
    public Collection<io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier> identifiersOfVersion(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version) {

      questions.merge("identifiersOfVersion(%s)".formatted(version), 1, Integer::sum);
      return canReadModels
          ? identifiersPerVersion.getOrDefault(version, List.of())
          : null;

    }

    private String whatIsMissed;

    @Override
    public String whatOlderVersionsMiss(
        final String workflowModuleId,
        final String bpmnProcessId) {

      questions.merge("whatOlderVersionsMiss", 1, Integer::sum);
      return whatIsMissed;

    }

  }

  private MigrationAdapterProperties properties;

  private WorkflowTaskRegistry registry;

  private CatalogStub catalog;

  /**
   * The check reads the version this boot deployed from here - the registry records it
   * through the SPI (see below), and the test drives the check directly.
   */
  private ProcessVersions processVersions;

  /**
   * ONE instance per test, as in an application: what it reported once it does not
   * report again.
   */
  private DeployedProcessVersionsCheck deployedVersionsCheck;

  @BeforeEach
  public void setUp() {

    properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("camunda7")));
    registry = new WorkflowTaskRegistry(new TransactionRunnerStub(), null, List.of(), properties);
    registry
        .registerWorkflowService(
            MODULE,
            PROCESS,
            Service.class,
            Service::new,
            type -> null,
            processService());
    registry.registerDeployedVersion(ADAPTER, MODULE, PROCESS, "3");
    processVersions = new ProcessVersions();
    processVersions.recordDeployedVersion(ADAPTER, MODULE, PROCESS, "3");
    deployedVersionsCheck = new DeployedProcessVersionsCheck(
        processVersions, new OutfadedProcessVersions(properties), registry::tasksNotServedInVersion, registry::handlersNotServingAnyVersion, registry);

    catalog = new CatalogStub();
    catalog.versions = List
        .of(DeployedProcessVersion.of("1"), DeployedProcessVersion.of("2"), DeployedProcessVersion.of("3"));
    catalog.tasksPerVersion = Map.of(
        "1", List.of(new BpmnTaskSpec("Activity_gone", "goneTask")),
        "2", List.of(new BpmnTaskSpec("Activity_old", "oldOnly")),
        "3", List.of(new BpmnTaskSpec("Activity_still", "stillThere")));

  }

  @Test
  @DisplayName("A version whose tasks are all served is not reported")
  public void aServedVersionIsQuiet() {

    catalog.tasksPerVersion = Map.of("1", List.of(new BpmnTaskSpec("Activity_still", "stillThere")), "2", List
        .of(new BpmnTaskSpec("Activity_old", "oldOnly")));

    assertEquals(List.of(), check());

  }

  @Test
  @DisplayName("The instance count of a version is asked ONCE, however many reports want it")
  public void theInstanceCountIsAskedOncePerVersion() {

    // an older version which is BOTH counted as running and reported as unserved: the
    // two reports used to ask the same question twice
    catalog.instancesPerVersion = Map.of("1", 4L);

    check(Level.ERROR);

    assertTrue(
        catalog.instanceCountQueries
            .values()
            .stream()
            .allMatch(queries -> queries == 1),
        () -> "one query per version and per run of the check, but was "
            + catalog.instanceCountQueries);
    assertTrue(
        catalog.instanceCountQueries.containsKey("1"),
        "the version which is both counted as running and reported as unserved was asked about");

  }

  @Test
  @DisplayName("A start asks the same questions whether two workflows ran or two million")
  public void theQuestionsDoNotDependOnHowManyWorkflowsRun() {

    catalog.instancesPerVersion = Map.of("1", 2L, "2", 1L);
    check(Level.ERROR);
    final var askedByAYoungApplication = Map.copyOf(catalog.questions);

    // the same application two years later: same models, same versions, and everything
    // which happened in between reflected in the numbers alone
    setUp();
    catalog.instancesPerVersion = Map.of("1", 2_400_000L, "2", 910_000L);
    check(Level.ERROR);

    assertEquals(
        askedByAYoungApplication,
        catalog.questions,
        "the number of questions a start asks belongs to the versions, never to the workflows");

  }

  @Test
  @DisplayName("Workflows on an older version are counted even where every task is served")
  public void workflowsOnAnOlderVersionAreCounted() {

    catalog.tasksPerVersion = Map.of("1", List.of(new BpmnTaskSpec("Activity_still", "stillThere")), "2", List
        .of(new BpmnTaskSpec("Activity_old", "oldOnly")));
    catalog.instancesPerVersion = Map.of("1", 3L);

    final var infos = check(Level.INFO);
    assertEquals(1, infos.size(), infos.toString());
    assertTrue(infos.get(0).contains("3 workflow(s)"), infos.get(0));
    assertTrue(infos.get(0).contains("older than the one adapter 'c7' deployed"), infos.get(0));
    assertTrue(infos.get(0).contains("falls to zero"), infos.get(0));

  }

  @Test
  @DisplayName("The adapter says what those workflows miss, and the count carries it")
  public void whatOlderWorkflowsMissIsNamed() {

    catalog.tasksPerVersion = Map.of("1", List.of(new BpmnTaskSpec("Activity_still", "stillThere")), "2", List
        .of(new BpmnTaskSpec("Activity_old", "oldOnly")));
    catalog.instancesPerVersion = Map.of("1", 3L);
    catalog.whatIsMissed = "the end of a workflow is not reported";

    final var infos = check(Level.INFO);

    assertEquals(1, infos.size(), infos.toString());
    assertTrue(infos.get(0).contains("the end of a workflow is not reported"), infos.get(0));

  }

  @Test
  @DisplayName("Workflows on an older version are no warning - nothing is wrong with them")
  public void workflowsOnAnOlderVersionAreNoWarning() {

    catalog.tasksPerVersion = Map.of("1", List.of(new BpmnTaskSpec("Activity_still", "stillThere")), "2", List
        .of(new BpmnTaskSpec("Activity_old", "oldOnly")));
    catalog.instancesPerVersion = Map.of("1", 3L);

    assertEquals(List.of(), check());

  }

  @Test
  @DisplayName("An older version nobody runs on is not worth a line")
  public void anOlderVersionWithoutWorkflowsIsQuiet() {

    catalog.tasksPerVersion = Map.of("1", List.of(new BpmnTaskSpec("Activity_still", "stillThere")), "2", List
        .of(new BpmnTaskSpec("Activity_old", "oldOnly")));

    assertEquals(List.of(), check(Level.INFO));

  }

  @Test
  @DisplayName("A BPMS which cannot count says nothing here, rather than reporting a zero")
  public void aBpmsWhichCannotCountIsQuietHere() {

    catalog.tasksPerVersion = Map.of("1", List.of(new BpmnTaskSpec("Activity_still", "stillThere")), "2", List
        .of(new BpmnTaskSpec("Activity_old", "oldOnly")));
    catalog.instancesPerVersion = Map.of("1", 3L);
    catalog.canCountInstances = false;

    assertEquals(List.of(), check(Level.INFO));

  }

  @Test
  @DisplayName("A version nobody runs whose task is unserved is a warning naming both ways out")
  public void anUnservedVersionWithoutInstancesWarns() {

    final var messages = check();

    assertEquals(1, messages.size(), messages.toString());
    final var message = messages.get(0);
    assertTrue(message.contains("'1'"), message);
    assertTrue(message.contains("'goneTask'"), message);
    assertTrue(message.contains("no workflow runs on it right now"), message);
    assertTrue(message.contains("version range covers version '1'"), message);
    assertTrue(message.contains("vanillabp.adapters.c7.outfaded-versions"), message);

  }

  @Test
  @DisplayName("Workflows running on an unserved version turn the warning into an error")
  public void anUnservedVersionWithInstancesIsAnError() {

    catalog.instancesPerVersion = Map.of("1", 7L);

    final var errors = check(Level.ERROR);

    assertEquals(1, errors.size(), errors.toString());
    assertTrue(errors.get(0).contains("7 workflow(s) still run on version '1'"), errors.get(0));
    assertTrue(errors.get(0).contains("incident"), errors.get(0));

  }

  @Test
  @DisplayName("A version the method serves by its version range is served, one it excludes is not")
  public void versionRangesDecidePerVersion() {

    // 'oldOnly' names version 2, so the same task in version 1 is unserved
    catalog.tasksPerVersion = Map.of("1", List.of(new BpmnTaskSpec("Activity_old", "oldOnly")), "2", List
        .of(new BpmnTaskSpec("Activity_old", "oldOnly")));

    final var messages = check();

    assertEquals(1, messages.size(), messages.toString());
    assertTrue(messages.get(0).contains("'1'"), messages.get(0));
    assertTrue(messages.get(0).contains("'oldOnly'"), messages.get(0));

  }

  @Test
  @DisplayName("Serving an old version does not mark a method as wired")
  public void servingAnOldVersionSaysNothingAboutTheDeployedModel() {

    check();

    // the method serving version 2 matched no task of the DEPLOYED model, which is
    // the direction validateNoUnwiredWorkflowTaskMethods decides
    final var failure = assertThrows(
        IllegalStateException.class,
        () -> {
          registry.validateTaskWiring(MODULE, PROCESS, List.of(new BpmnTaskSpec("Activity_still", "stillThere")));
          registry.validateNoUnwiredWorkflowTaskMethods(MODULE);
        });
    assertTrue(failure.getMessage().contains("oldOnly"), failure.getMessage());

  }

  @Test
  @DisplayName("A method serving no held version is reported as dead, naming what it names")
  public void aMethodServingNoHeldVersionIsReported() {

    registry
        .registerWorkflowService(
            MODULE,
            "DeadProcess",
            DeadVersionService.class,
            DeadVersionService::new,
            type -> null,
            processService());
    processVersions.recordDeployedVersion(ADAPTER, MODULE, "DeadProcess", "3");

    final var message = deadMethodMessage(check(Level.WARN, "DeadProcess"));

    assertTrue(message.contains("@WorkflowTask method"), message);
    assertTrue(message.contains("neverRuns"), message);
    assertTrue(message.contains("'99'"), message);
    assertTrue(message.contains("held: 1, 2, 3"), message);

  }

  @Test
  @DisplayName("Fading a version out makes the method serving it dead, and the message says so")
  public void outfadingMakesAMethodDead() {

    // 'oldOnly' serves version 2 only, which is now faded out
    outfade("2");

    final var message = deadMethodMessage(check());

    assertTrue(message.contains("oldOnly"), message);
    assertTrue(message.contains("faded out by 'vanillabp.adapters.c7.outfaded-versions'"), message);

  }

  @Test
  @DisplayName("An outfaded version is not checked at all")
  public void anOutfadedVersionIsSkipped() {

    outfade("1");

    assertEquals(List.of(), check());

  }

  @Test
  @DisplayName("Workflows on an outfaded version are FATAL, and the policy decides whether the boot survives")
  public void workflowsOnAnOutfadedVersionAreReported() {

    outfade("<3");
    catalog.instancesPerVersion = Map.of("1", 2L, "2", 3L);

    final var errors = check(Level.ERROR);

    assertEquals(2, errors.size(), errors.toString());
    assertTrue(errors.get(0).contains("2 workflow(s) still run on version '1'"), errors.get(0));
    assertTrue(errors.get(0).contains("outfaded-versions-in-use"), errors.get(0));

    properties
        .getAdapters()
        .get(ADAPTER)
        .setOutfadedVersionsInUse(OutfadedVersionsInUsePolicy.FAIL);
    final var failure = assertThrows(IllegalStateException.class, this::runCheck);
    assertTrue(failure.getMessage().contains("still run on version"), failure.getMessage());

  }

  @Test
  @DisplayName("Fading out the version this boot deployed fails the start")
  public void outfadingTheDeployedVersionFailsTheBoot() {

    outfade("<=3");

    final var failure = assertThrows(IllegalStateException.class, this::runCheck);

    assertTrue(failure.getMessage().contains("deployed during this boot"), failure.getMessage());
    assertTrue(failure.getMessage().contains("'<=3'"), failure.getMessage());
    assertTrue(failure.getMessage().contains("'<3'"), failure.getMessage());

  }

  @Test
  @DisplayName("A BPMS which cannot read old models says so once")
  public void aBpmsWhichCannotReadModelsIsReportedOnce() {

    catalog.canReadModels = false;

    final var first = check();
    final var second = check();

    assertEquals(1, first.size(), first.toString());
    assertTrue(first.get(0).contains("cannot read the models"), first.get(0));
    assertEquals(List.of(), second, "the same adapter does not repeat itself");

  }

  @Test
  @DisplayName("A BPMS which cannot count workflows keeps outfading and says why")
  public void aBpmsWhichCannotCountInstancesKeepsOutfading() {

    outfade("1");
    catalog.canCountInstances = false;

    final var messages = check();

    assertEquals(1, messages.size(), messages.toString());
    assertTrue(messages.get(0).contains("cannot say how many workflows"), messages.get(0));
    assertTrue(messages.get(0).contains("stay faded out"), messages.get(0));

  }

  @Test
  @DisplayName("A broken specification names the grammar")
  public void aBrokenSpecificationIsReportedWithTheGrammar() {

    outfade(">");

    final var failure = assertThrows(IllegalStateException.class, this::runCheck);

    assertTrue(failure.getMessage().contains("Unsupported version specification"), failure.getMessage());
    assertTrue(failure.getMessage().contains("outfaded-versions"), failure.getMessage());

  }

  /**
   * The registry needs a process service to register a workflow service; nothing here
   * invokes it.
   */
  @SuppressWarnings("unchecked")
  private static io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService<Aggregate> processService() {

    final var processService = org.mockito.Mockito
        .mock(io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService.class);
    org.mockito.Mockito
        .when(processService.getWorkflowAggregateClass())
        .thenReturn((Class) Aggregate.class);
    return processService;

  }

  /**
   * The one message about a method which never runs - the same check also reports the
   * unserved tasks of the versions that method left behind.
   */
  private static String deadMethodMessage(
      final List<String> messages) {

    return messages
        .stream()
        .filter(message -> message.contains("the method never runs"))
        .reduce((
            first,
            second) -> {
          throw new AssertionError("more than one dead method reported: "
              + messages);
        })
        .orElseThrow(() -> new AssertionError("no dead method reported: "
            + messages));

  }

  private void outfade(
      final String... specifications) {

    properties
        .getAdapters()
        .get(ADAPTER)
        .setOutfadedVersions(List.of(specifications));

  }

  private String checkedProcess = PROCESS;

  @Test
  @DisplayName("The identifiers of a held version are read in the loop which reads its model")
  public void theIdentifiersOfAHeldVersionAreReported() {

    final var message = new io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier(
        io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ScopedIdentifierKind.MESSAGE_NAME, "PaymentReceived", null);
    catalog.identifiersPerVersion = Map.of("1", List.of(message), "2", List.of(message));
    catalog.instancesPerVersion = Map.of("1", 4L);
    final var reported = new java.util.LinkedHashMap<String, Long>();
    deployedVersionsCheck = new DeployedProcessVersionsCheck(
        processVersions, new OutfadedProcessVersions(properties), registry::tasksNotServedInVersion, registry::handlersNotServingAnyVersion, registry, null, (
            adapterId,
            workflowModuleId,
            bpmnProcessId,
            version,
            activeWorkflows,
            declared) -> reported.put(version, activeWorkflows));

    check();

    // the versions OLDER than the one this boot deployed, which is the same set the
    // unserved-task report walks, and the instance count it already asked for
    assertEquals(java.util.Set.of("1", "2"), reported.keySet(), () -> String.valueOf(reported));
    assertEquals(4L, reported.get("1"));
    assertEquals(0L, reported.get("2"));
    // one question per version, never one per workflow
    assertEquals(1, catalog.questions.get("identifiersOfVersion(1)"));
    assertEquals(1, catalog.questions.get("identifiersOfVersion(2)"));

  }

  @Test
  @DisplayName("A BPMS which cannot read a held model reports no identifiers of it")
  public void aBpmsWhichCannotReadModelsReportsNoIdentifiers() {

    catalog.canReadModels = false;
    final var reported = new java.util.LinkedHashMap<String, Long>();
    deployedVersionsCheck = new DeployedProcessVersionsCheck(
        processVersions, new OutfadedProcessVersions(properties), registry::tasksNotServedInVersion, registry::handlersNotServingAnyVersion, registry, null, (
            adapterId,
            workflowModuleId,
            bpmnProcessId,
            version,
            activeWorkflows,
            declared) -> reported.put(version, activeWorkflows));

    check();

    assertEquals(Map.of(), reported, () -> "nothing is judged by an answer nobody has: "
        + reported);

  }

  private void runCheck() {

    deployedVersionsCheck.check(MODULE, checkedProcess, ADAPTER, catalog, VersionRange.NO_RESOLVER);
    // a method which never runs is a statement about the whole workflow module, so the
    // check draws it once the versions of every process of the module were read
    deployedVersionsCheck.reportDeadHandlers(MODULE);

  }

  private List<String> check() {

    return check(Level.WARN);

  }

  private List<String> check(
      final Level level) {

    return check(level, PROCESS);

  }

  private List<String> check(
      final Level level,
      final String bpmnProcessId) {

    checkedProcess = bpmnProcessId;
    final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logWatcher.start();
    final var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
        .getLogger(DeployedProcessVersionsCheck.class);
    logger.addAppender(logWatcher);
    try {
      runCheck();
    } finally {
      logger.detachAndStopAllAppenders();
    }
    return logWatcher.list
        .stream()
        .filter(event -> event.getLevel().isGreaterOrEqual(level))
        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
        .toList();

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
