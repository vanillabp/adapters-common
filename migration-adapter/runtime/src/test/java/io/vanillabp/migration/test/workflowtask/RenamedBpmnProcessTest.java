package io.vanillabp.migration.test.workflowtask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
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
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;
import io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import lombok.Getter;

/**
 * Renaming a BPMN process. The application brings the new id, while the BPMS keeps the old one
 * with every version ever deployed under it and with the workflows still running on them, and
 * <code>&#64;WorkflowService(secondaryBpmnProcesses = ...)</code> is how the application says
 * that it answers for both.
 * <p>
 * The declaration alone was never the whole story: an adapter registers the versions of the
 * processes it just deployed, and nothing was deployed under the old id, so the startup check
 * for old process versions never looked at the versions the workflows out there run on. Here
 * the core asks the adapter about the declared id, and every verdict of that check applies to
 * it - with "nothing deployed under this id" meaning that every version the BPMS holds is an
 * older one.
 * <p>
 * The BPMS is a stub, because what is asked here is the core's arithmetic; the adapters prove
 * against real engines that their answer for a renamed id is the right one.
 */
@ExtendWith(SuppressOutputExtension.class)
public class RenamedBpmnProcessTest {

  private static final String MODULE = "rename-module";

  private static final String NEW_ID = "OrderApproval";

  private static final String OLD_ID = "order_approval";

  private static final String CALLED_ID = "OrderShipment";

  private static final String ADAPTER = "c8";

  @Getter
  public static class Aggregate {

    String id;

  }

  /**
   * The application after the rename: the new id as its process, the old id as a secondary
   * process carrying the versions the BPMS still holds under it. The task 'approve' exists in
   * both generations of the model.
   */
  @WorkflowService(
      workflowAggregateClass = Aggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = NEW_ID),
      secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = OLD_ID, version = "1-3"))
  public static class RenamedService {

    @WorkflowTask(taskDefinition = "approve")
    public void approve(
        final Aggregate aggregate) {
    }

  }

  /**
   * The same rename where the new generation of the model DROPPED a task: 'checkCredit' exists
   * in the versions the BPMS holds under the old id and nowhere else, which is what its
   * version range says.
   */
  @WorkflowService(
      workflowAggregateClass = Aggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = NEW_ID),
      secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = OLD_ID, version = "1-3"))
  public static class ServiceKeepingTheOldGeneration {

    @WorkflowTask(taskDefinition = "approve")
    public void approve(
        final Aggregate aggregate) {
    }

    @WorkflowTask(taskDefinition = "checkCredit", version = "1-3")
    public void checkCredit(
        final Aggregate aggregate) {
    }

  }

  /**
   * A method naming a version no BPMS holds anywhere - the case the dead-method report exists
   * for, next to the one it must not report.
   */
  @WorkflowService(
      workflowAggregateClass = Aggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = NEW_ID),
      secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = OLD_ID, version = "1-3"))
  public static class ServiceWithAMethodForNothing {

    @WorkflowTask(taskDefinition = "approve")
    public void approve(
        final Aggregate aggregate) {
    }

    @WorkflowTask(taskDefinition = "gone", version = "99")
    public void neverRuns(
        final Aggregate aggregate) {
    }

  }

  /**
   * A rename of a process which was a secondary process before it was renamed: the module
   * deploys a called process as well, and its declaration is not the one nothing was deployed
   * under.
   */
  @WorkflowService(
      workflowAggregateClass = Aggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = NEW_ID),
      secondaryBpmnProcesses = {
          @BpmnProcess(bpmnProcessId = CALLED_ID), @BpmnProcess(bpmnProcessId = OLD_ID, version = "1-3")
      })
  public static class ServiceWithACalledProcess {

    @WorkflowTask(taskDefinition = "approve")
    public void approve(
        final Aggregate aggregate) {
    }

    @WorkflowTask(taskDefinition = "ship")
    public void ship(
        final Aggregate aggregate) {
    }

  }

  /**
   * A second workflow service of the module, serving the called process on its own. One of
   * its methods names a task no model of that process carries, which is the defect the
   * reverse wiring check exists for and has nothing to do with the rename next to it.
   */
  @WorkflowService(
      workflowAggregateClass = Aggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = CALLED_ID))
  public static class ShipmentServiceWithAnOrphanMethod {

    @WorkflowTask(taskDefinition = "ship")
    public void ship(
        final Aggregate aggregate) {
    }

    @WorkflowTask(taskDefinition = "shipp")
    public void shipTypo(
        final Aggregate aggregate) {
    }

  }

  /**
   * A rename whose kept method is wired to a BPMN ELEMENT rather than to a task
   * definition. There is no model of the old id to read that element's task definition
   * off, so nothing an adapter could subscribe to comes out of this declaration.
   */
  @WorkflowService(
      workflowAggregateClass = Aggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = NEW_ID),
      secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = OLD_ID, version = "1-3"))
  public static class ServiceWiredToAnElementOfTheOldModel {

    @WorkflowTask(taskDefinition = "approve")
    public void approve(
        final Aggregate aggregate) {
    }

    @WorkflowTask(id = "Activity_approve")
    public void approveByElement(
        final Aggregate aggregate) {
    }

  }

  /**
   * What a BPMS holds per BPMN process id, with every answer handed in by the test.
   */
  private static class CatalogStub implements ProcessVersionCatalog {

    private final Map<String, List<DeployedProcessVersion>> versions = new java.util.HashMap<>();

    private final Map<String, Collection<BpmnTaskSpec>> tasks = new java.util.HashMap<>();

    private final Map<String, Long> instances = new java.util.HashMap<>();

    /**
     * Every question this BPMS was asked, in order. A start asks about the versions of a
     * process, never about the workflows which ran through it - decision 19 in the
     * repository's DECISIONS.md - so what is asked about an id nothing was deployed under is
     * worth reading in a test.
     */
    private final List<String> questions = new ArrayList<>();

    @Override
    public List<DeployedProcessVersion> deployedVersionsOf(
        final String workflowModuleId,
        final String bpmnProcessId) {

      questions.add("deployedVersionsOf(%s)".formatted(bpmnProcessId));
      return versions.getOrDefault(bpmnProcessId, List.of());

    }

    @Override
    public DeployedProcessVersion resolveVersion(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String versionOrVersionTag) {

      return versions
          .getOrDefault(bpmnProcessId, List.of())
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

      questions.add("tasksOfVersion(%s,%s)".formatted(bpmnProcessId, version));
      return tasks.getOrDefault("%s|%s".formatted(bpmnProcessId, version), List.of());

    }

    @Override
    public Long activeInstanceCountOf(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version) {

      questions.add("activeInstanceCountOf(%s,%s)".formatted(bpmnProcessId, version));
      return instances.get("%s|%s".formatted(bpmnProcessId, version));

    }

    @Override
    public String whatOlderVersionsMiss(
        final String workflowModuleId,
        final String bpmnProcessId) {

      return "the end of a workflow is not reported";

    }

  }

  private MigrationAdapterProperties properties;

  private WorkflowTaskRegistry registry;

  private CatalogStub catalog;

  @BeforeEach
  public void setUp() {

    properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("camunda8")));
    registry = new WorkflowTaskRegistry(new TransactionRunnerStub(), null, List.of(), properties);

    catalog = new CatalogStub();
    // three versions under the old id, and the one this boot deployed under the new one
    catalog.versions
        .put(OLD_ID, List
            .of(DeployedProcessVersion.of("1"), DeployedProcessVersion.of("2"), DeployedProcessVersion.of("3")));
    catalog.versions.put(NEW_ID, List.of(DeployedProcessVersion.of("1")));
    catalog.versions.put(CALLED_ID, List.of(DeployedProcessVersion.of("1")));
    catalog.tasks.put("%s|1".formatted(OLD_ID), List.of(new BpmnTaskSpec("Activity_credit", "checkCredit")));
    catalog.tasks.put("%s|2".formatted(OLD_ID), List.of(new BpmnTaskSpec("Activity_approve", "approve")));
    catalog.tasks.put("%s|3".formatted(OLD_ID), List.of(new BpmnTaskSpec("Activity_approve", "approve")));
    catalog.tasks.put("%s|1".formatted(NEW_ID), List.of(new BpmnTaskSpec("Activity_approve", "approve")));

  }

  @Test
  @DisplayName("The versions of the old id are read and their unserved tasks reported")
  public void theOldIdIsCheckedThroughTheAnswerOfTheAdapter() {

    theApplicationDeclares(RenamedService.class);
    theAdapterDeployed(NEW_ID, "1");

    final var reports = theModuleFinishedDeploying(Level.WARN);

    assertTrue(
        catalog.questions.contains("deployedVersionsOf(%s)".formatted(OLD_ID)),
        () -> "the BPMS was never asked about the old id: "
            + catalog.questions);
    assertEquals(1, reports.size(), reports.toString());
    assertTrue(reports.get(0).contains("'1'"), reports.get(0));
    assertTrue(reports.get(0).contains("'checkCredit'"), reports.get(0));
    assertTrue(reports.get(0).contains(OLD_ID), reports.get(0));

  }

  @Test
  @DisplayName("An adapter which cannot answer leaves everything as it was")
  public void anAdapterAnsweringNothingChangesNothing() {

    theApplicationDeclares(RenamedService.class);
    theAdapterDeployed(NEW_ID, "1");

    final var reports = theModuleFinishedDeploying(Level.WARN, (
        module,
        process) -> null);

    assertEquals(List.of(), reports, "an adapter which cannot tell switches the check off, as before");
    assertFalse(
        catalog.questions.contains("deployedVersionsOf(%s)".formatted(OLD_ID)),
        () -> "nothing may be asked about the old id: "
            + catalog.questions);

  }

  @Test
  @DisplayName("Only an id nothing was deployed under is asked about")
  public void theDeployedIdIsNotAskedAboutAgain() {

    theApplicationDeclares(RenamedService.class);
    theAdapterDeployed(NEW_ID, "1");
    final var askedAbout = new ArrayList<String>();

    theModuleFinishedDeploying(Level.WARN, (
        module,
        process) -> {
      askedAbout.add(process);
      return catalog;
    });

    assertEquals(List.of(OLD_ID), askedAbout, "the adapter registered the versions of what it deployed itself");

  }

  @Test
  @DisplayName("Workflows still running on the old id are counted and said to be served")
  public void workflowsOnTheOldIdAreCounted() {

    theApplicationDeclares(RenamedService.class);
    theAdapterDeployed(NEW_ID, "1");
    // version 1 is the one whose task nobody serves, so version 2 carries the workflows
    catalog.instances.put("%s|2".formatted(OLD_ID), 5L);

    final var reports = theModuleFinishedDeploying(Level.INFO);

    final var counted = reports
        .stream()
        .filter(report -> report.contains("5 workflow(s)"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("the workflows on the old id were not counted: "
            + reports));
    assertTrue(counted.contains("does not deploy any more"), counted);
    assertTrue(counted.contains("secondaryBpmnProcesses"), counted);
    assertTrue(counted.contains("the end of a workflow is not reported"), counted);
    assertTrue(counted.contains("the declaration and the methods serving it can go"), counted);

  }

  @Test
  @DisplayName("Workflows on a version of the old id nobody serves are an error naming the incident")
  public void workflowsOnAnUnservedVersionOfTheOldIdAreAnError() {

    theApplicationDeclares(RenamedService.class);
    theAdapterDeployed(NEW_ID, "1");
    catalog.instances.put("%s|1".formatted(OLD_ID), 7L);

    final var errors = theModuleFinishedDeploying(Level.ERROR);

    assertEquals(1, errors.size(), errors.toString());
    assertTrue(errors.get(0).contains("7 workflow(s) still run on version '1'"), errors.get(0));
    assertTrue(errors.get(0).contains("'checkCredit'"), errors.get(0));
    assertTrue(errors.get(0).contains("incident"), errors.get(0));

  }

  @Test
  @DisplayName("Fading out the old id's versions while workflows run on them ends the boot with the FAIL policy")
  public void outfadedVersionsOfTheOldIdInUseEndTheBoot() {

    // the versions of the old id are faded out where they belong, per workflow: each BPMN
    // process id is counted from 1 by itself, so the same specification at adapter level
    // would cover the version this boot deployed under the NEW id and end the boot for
    // that reason instead
    fadeOutForTheOldId("1-3", OutfadedVersionsInUsePolicy.FAIL);
    theApplicationDeclares(RenamedService.class);
    theAdapterDeployed(NEW_ID, "1");
    catalog.instances.put("%s|2".formatted(OLD_ID), 3L);

    final var failure = assertThrows(IllegalStateException.class, () -> theModuleFinishedDeploying(Level.ERROR));

    assertTrue(failure.getMessage().contains("3 workflow(s) still run on version '2'"), failure.getMessage());
    assertTrue(failure.getMessage().contains(OLD_ID), failure.getMessage());

  }

  @Test
  @DisplayName("A method kept for the old id is not reported as a method which never runs")
  public void aMethodOfTheOldGenerationIsNotDead() {

    theApplicationDeclares(ServiceKeepingTheOldGeneration.class);
    // the rename happened a while ago: the new id was deployed a few times since
    catalog.versions
        .put(NEW_ID, List.of(DeployedProcessVersion.of("4"), DeployedProcessVersion.of("5")));
    catalog.tasks.put("%s|4".formatted(NEW_ID), List.of(new BpmnTaskSpec("Activity_approve", "approve")));
    theAdapterDeployed(NEW_ID, "5");

    final var reports = theModuleFinishedDeploying(Level.WARN);

    assertTrue(
        reports.stream().noneMatch(report -> report.contains("the method never runs")),
        () -> "the method serving versions 1-3 of the old id runs: "
            + reports);

  }

  @Test
  @DisplayName("A method serving no version of any of its processes is still reported")
  public void aMethodServingNothingAnywhereIsDead() {

    theApplicationDeclares(ServiceWithAMethodForNothing.class);
    theAdapterDeployed(NEW_ID, "1");

    final var reports = theModuleFinishedDeploying(Level.WARN);

    final var dead = reports
        .stream()
        .filter(report -> report.contains("the method never runs"))
        .toList();
    assertFalse(dead.isEmpty(), () -> "the method naming version 99 was not reported: "
        + reports);
    dead.forEach(report -> assertTrue(report.contains("neverRuns"), report));

  }

  @Test
  @DisplayName("A method kept for a task the new model dropped does not fail the start")
  public void aMethodOfTheOldGenerationIsNotUnwired() {

    theApplicationDeclares(ServiceKeepingTheOldGeneration.class);
    theAdapterDeployed(NEW_ID, "1");

    // what the deployment pipeline does once every adapter of the module deployed
    registry.registerVersionsOfProcessesNobodyDeployed(MODULE, ADAPTER, (
        module,
        process) -> catalog);
    registry.validateNoUnwiredWorkflowTaskMethods(MODULE);

  }

  @Test
  @DisplayName("A declared id the BPMS holds nothing under is one warning naming what is deployed")
  public void aDeclaredIdWithoutAnyVersionIsReported() {

    theApplicationDeclares(RenamedService.class);
    theAdapterDeployed(NEW_ID, "1");
    // the last workflow of the old id ended and the BPMS forgot the definition - or the
    // id is a typo, which looks exactly the same from here
    catalog.versions.remove(OLD_ID);

    final var reports = theModuleFinishedDeploying(Level.WARN);

    assertEquals(1, reports.size(), reports.toString());
    assertTrue(reports.get(0).contains(OLD_ID), reports.get(0));
    assertTrue(reports.get(0).contains("secondaryBpmnProcesses"), reports.get(0));
    assertTrue(reports.get(0).contains("'%s'".formatted(NEW_ID)), reports.get(0));

  }

  @Test
  @DisplayName("A secondary process which WAS deployed is none of this business")
  public void aDeployedSecondaryProcessIsNotAskedAbout() {

    theApplicationDeclares(ServiceWithACalledProcess.class);
    theAdapterDeployed(NEW_ID, "1");
    theAdapterDeployed(CALLED_ID, "1", new BpmnTaskSpec("Activity_ship", "ship"));
    final var askedAbout = new ArrayList<String>();

    final var reports = theModuleFinishedDeploying(Level.WARN, (
        module,
        process) -> {
      askedAbout.add(process);
      return catalog;
    });

    assertEquals(List.of(OLD_ID), askedAbout, "only the id nothing was deployed under");
    assertTrue(
        reports.stream().noneMatch(report -> report.contains(CALLED_ID)),
        () -> "the called process is deployed and needs no report: "
            + reports);

  }

  @Test
  @DisplayName("A workflow service whose processes were none of them deployed asks nobody")
  public void aWorkflowServiceWaitingForItsModelAsksNobody() {

    theApplicationDeclares(RenamedService.class);
    final var askedAbout = new ArrayList<String>();

    theModuleFinishedDeploying(Level.WARN, (
        module,
        process) -> {
      askedAbout.add(process);
      return catalog;
    });

    assertEquals(
        List.of(),
        askedAbout,
        "a workflow service waiting for a model which has not arrived says nothing about a rename");

  }

  @Test
  @DisplayName("A process nobody claims next to a rename is reported without touching the kept method")
  public void anUnclaimedProcessNextToARenameLeavesTheKeptMethodAlone() {

    theApplicationDeclares(ServiceKeepingTheOldGeneration.class);
    theAdapterDeployed(NEW_ID, "1");
    // a BPMN file of the module carries a process nobody serves - it is collected, not
    // validated, and the boot goes on to the module-level checks
    registry
        .validateTaskWiring(
            MODULE,
            "SomebodyElsesProcess",
            List.of(new BpmnTaskSpec("Activity_1", "somebodyElsesTask")));

    registry.registerVersionsOfProcessesNobodyDeployed(MODULE, ADAPTER, (
        module,
        process) -> catalog);
    registry.validateNoUnwiredWorkflowTaskMethods(MODULE);

    assertEquals(
        List.of("SomebodyElsesProcess"),
        List.copyOf(registry.bpmnProcessesWithoutWorkflowService(MODULE)),
        "the old id is declared, so it is served rather than unclaimed");

  }

  @Test
  @DisplayName("A method matching nothing is still reported while a rename is in the same module")
  public void aMethodMatchingNothingIsReportedNextToARename() {

    theApplicationDeclares(ServiceKeepingTheOldGeneration.class);
    theApplicationDeclares(ShipmentServiceWithAnOrphanMethod.class);
    theAdapterDeployed(NEW_ID, "1");
    theAdapterDeployed(CALLED_ID, "1", new BpmnTaskSpec("Activity_ship", "ship"));
    registry
        .validateTaskWiring(
            MODULE,
            "SomebodyElsesProcess",
            List.of(new BpmnTaskSpec("Activity_1", "somebodyElsesTask")));
    registry.registerVersionsOfProcessesNobodyDeployed(MODULE, ADAPTER, (
        module,
        process) -> catalog);

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> registry.validateNoUnwiredWorkflowTaskMethods(MODULE));

    assertTrue(exception.getMessage().contains("shipTypo"), exception.getMessage());
    assertFalse(
        exception.getMessage().contains("checkCredit"),
        () -> "the method kept for the versions of the old id runs: "
            + exception.getMessage());

  }

  @Test
  @DisplayName("The task definitions served for the old id are named, so an adapter can subscribe to them")
  public void theTaskDefinitionsOfTheOldIdAreNamed() {

    theApplicationDeclares(ServiceKeepingTheOldGeneration.class);
    theAdapterDeployed(NEW_ID, "1");

    assertEquals(
        Map.of(OLD_ID, List.of("approve", "checkCredit")),
        registry.taskDefinitionsOfProcessesNobodyDeployed(MODULE),
        "what the application serves for the old id is what an adapter needs to reach those workflows");

  }

  @Test
  @DisplayName("A method wired to a BPMN element names no task definition for the old id")
  public void aMethodWiredToAnElementNamesNoTaskDefinition() {

    theApplicationDeclares(ServiceWiredToAnElementOfTheOldModel.class);
    theAdapterDeployed(NEW_ID, "1");

    assertEquals(
        Map.of(OLD_ID, List.of("approve")),
        registry.taskDefinitionsOfProcessesNobodyDeployed(MODULE),
        "the method wired to an element contributes nothing an adapter could subscribe to");

  }

  @Test
  @DisplayName("Only an id nothing was deployed under is named, and a module without a rename names nothing")
  public void onlyAnIdNobodyDeployedIsNamed() {

    theApplicationDeclares(ServiceWithACalledProcess.class);
    theAdapterDeployed(NEW_ID, "1");
    theAdapterDeployed(CALLED_ID, "1", new BpmnTaskSpec("Activity_ship", "ship"));

    assertEquals(
        java.util.Set.of(OLD_ID),
        registry.taskDefinitionsOfProcessesNobodyDeployed(MODULE).keySet(),
        "the called process was deployed, so it is not what a rename left behind");
    assertEquals(
        Map.of(),
        registry.taskDefinitionsOfProcessesNobodyDeployed("another-module"),
        "a workflow module without a declared-only id names nothing");

  }

  @Test
  @DisplayName("A workflow service waiting for its model names nothing either")
  public void aWorkflowServiceWaitingForItsModelNamesNothing() {

    theApplicationDeclares(RenamedService.class);

    assertEquals(
        Map.of(),
        registry.taskDefinitionsOfProcessesNobodyDeployed(MODULE),
        "the same answer the version question gives: nothing here says a process was renamed");

  }

  /**
   * Fades out versions of the OLD id alone, which is where a rename puts them:
   * <code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;old id&gt;.adapters.&lt;adapter&gt;.outfaded-versions</code>.
   */
  private void fadeOutForTheOldId(
      final String specification,
      final OutfadedVersionsInUsePolicy policy) {

    final var oldProcess = io.vanillabp.integration.adapter.migration.config.WorkflowAdapterProperties
        .builder()
        .adapters(
            Map
                .of(
                    ADAPTER,
                    io.vanillabp.integration.adapter.migration.config.AdapterProperties
                        .builder()
                        .outfadedVersions(List.of(specification))
                        .outfadedVersionsInUse(policy)
                        .build()))
        .build();
    final var workflowModule = io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties
        .builder()
        .workflows(Map.of(OLD_ID, oldProcess))
        .build();
    properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("camunda8")))
        .prioritizedAdapters(List.of(ADAPTER))
        .workflowModules(Map.of(MODULE, workflowModule))
        .build();
    properties.validateAndLink();
    registry = new WorkflowTaskRegistry(new TransactionRunnerStub(), null, List.of(), properties);

  }

  /**
   * Registers the workflow service class under every BPMN process id it declares, which is
   * what the platform integration does at startup.
   */
  private void theApplicationDeclares(
      final Class<?> workflowServiceClass) {

    java.util.stream.Stream
        .concat(
            java.util.stream.Stream.of(workflowServiceClass.getAnnotation(WorkflowService.class).bpmnProcess()),
            java.util.stream.Stream
                .of(workflowServiceClass.getAnnotation(WorkflowService.class).secondaryBpmnProcesses()))
        .map(BpmnProcess::bpmnProcessId)
        .forEach(bpmnProcessId -> registry
            .registerWorkflowService(
                MODULE,
                bpmnProcessId,
                workflowServiceClass,
                () -> null,
                type -> null,
                processService()));

  }

  /**
   * What an adapter does for a BPMN process it really deployed: it wires the model, registers
   * what its BPMS knows about that process and reports the version it was given.
   */
  private void theAdapterDeployed(
      final String bpmnProcessId,
      final String version,
      final BpmnTaskSpec... tasks) {

    registry
        .validateTaskWiring(
            MODULE,
            bpmnProcessId,
            tasks.length == 0
                ? List.of(new BpmnTaskSpec("Activity_approve", "approve"))
                : List.of(tasks));
    registry.registerProcessVersions(ADAPTER, MODULE, bpmnProcessId, catalog);
    registry.registerDeployedVersion(ADAPTER, MODULE, bpmnProcessId, version);

  }

  private List<String> theModuleFinishedDeploying(
      final Level level) {

    return theModuleFinishedDeploying(level, (
        module,
        process) -> catalog);

  }

  /**
   * The two steps the deployment pipeline runs once every adapter of a workflow module
   * deployed: the core asks each adapter about the ids nothing was deployed under, then the
   * versions are resolved and checked.
   */
  private List<String> theModuleFinishedDeploying(
      final Level level,
      final BiFunction<String, String, ProcessVersionCatalog> catalogOfProcess) {

    final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logWatcher.start();
    final var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
        .getLogger(DeployedProcessVersionsCheck.class);
    logger.addAppender(logWatcher);
    try {
      registry.registerVersionsOfProcessesNobodyDeployed(MODULE, ADAPTER, catalogOfProcess);
      registry.resolveProcessVersions(MODULE);
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
