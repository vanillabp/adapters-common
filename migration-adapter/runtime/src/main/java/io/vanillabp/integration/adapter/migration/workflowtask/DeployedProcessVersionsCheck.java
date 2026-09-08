package io.vanillabp.integration.adapter.migration.workflowtask;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.migration.config.OutfadedVersionsInUsePolicy;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;

/**
 * The startup check for old process versions: a BPMS keeps every version of a process
 * it was ever given, and workflows keep running on them, while the application only
 * brings the newest model with it. Whether the application still SERVES the older
 * versions is therefore a question worth asking while it boots - without it the first
 * news of a version nobody serves is an incident on a live workflow.
 * <p>
 * The check runs once per BPMN process after its workflow module was deployed. Reading
 * an old model belongs to the adapter ({@link ProcessVersionCatalogAccess}), deciding
 * whether a method serves it belongs to the core, and the two ends meet here.
 * <p>
 * "Older than what this boot deployed" has two readings, and both are ordinary. Where a
 * model was deployed under that id, the version the BPMS assigned to it is the border.
 * Where the application DECLARES the id without bringing a model for it - what renaming a
 * BPMN process leaves behind, the old id living on in the BPMS with the workflows still
 * running on it - there is no border and every version the BPMS holds is an older one.
 * <p>
 * How loud a finding is depends on whether workflows still run on that version: a
 * version nobody runs is a warning, a version with running workflows is FATAL and,
 * where the operator asked for it, the end of the boot.
 * <p>
 * Why the core drives this check while an adapter only answers two questions is decision 15 in the
 * repository's DECISIONS.md.
 *
 * <h2>What one run of it costs</h2>
 *
 * One question for the versions the BPMS holds, and then two per version OLDER than the one this
 * boot deployed: the model of that version, and how many workflows still run on it. A third is
 * asked only where workflows do run on such a version - which of its elements can put a second
 * token into one of them - so a version nobody is on costs nothing extra. The cost therefore
 * follows the number of versions, which grows when somebody deploys a changed model and which
 * <code>outfaded-versions</code> is the operator's way to bound. It does not follow the number of
 * workflows, and it must not start to - decision 19.
 * <p>
 * A BPMN process id the application declares without deploying a model under it is one such
 * process more, asked about like any other. That number follows the declarations of the
 * application, which change when somebody edits them.
 */
public class DeployedProcessVersionsCheck {

  private static final Logger log = LoggerFactory.getLogger(DeployedProcessVersionsCheck.class);

  /**
   * Which of the given tasks no <code>&#64;WorkflowTask</code> method serves in that
   * version - answered by the {@link WorkflowTaskRegistry}, which is the only place
   * knowing the version ranges of the methods.
   */
  @FunctionalInterface
  public interface UnservedTasks {

    Collection<BpmnTaskSpec> of(
        String workflowModuleId,
        String bpmnProcessId,
        String version,
        Collection<BpmnTaskSpec> tasks);

  }

  /**
   * Which methods registered for one BPMN process serve none of the versions worth
   * serving, in that process and in every other BPMN process of the workflow module they
   * are registered for - answered by the {@link WorkflowTaskRegistry} for all three
   * annotations carrying a <code>version</code> attribute.
   */
  @FunctionalInterface
  public interface DeadHandlers {

    List<String> of(
        String workflowModuleId,
        String bpmnProcessId,
        java.util.Map<String, Collection<String>> servableVersionsByProcess);

  }

  /**
   * The elements of the versions a BPMS still holds which can put a second token into a
   * running workflow - handed to the {@link WorkflowTaskRegistry}, which is the only place
   * knowing the workflow aggregate of a BPMN process and therefore the only one which can
   * decide what the finding means.
   */
  @FunctionalInterface
  public interface ConcurrentTokenElementsOfHeldVersions {

    void report(
        String workflowModuleId,
        String bpmnProcessId,
        java.util.Map<String, Collection<String>> elementIdsByVersion);

  }

  /**
   * What the check reads from an adapter's catalog - narrowed to its two questions so a
   * test double does not have to be a whole catalog.
   */
  public interface ProcessVersionCatalogAccess {

    List<DeployedProcessVersion> deployedVersionsOf(
        String workflowModuleId,
        String bpmnProcessId);

    Collection<BpmnTaskSpec> tasksOfVersion(
        String workflowModuleId,
        String bpmnProcessId,
        String version);

    Long activeInstanceCountOf(
        String workflowModuleId,
        String bpmnProcessId,
        String version);

    default String whatOlderVersionsMiss(
        final String workflowModuleId,
        final String bpmnProcessId) {

      return null;

    }

    default Collection<String> concurrentTokenElementsOfVersion(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version) {

      return null;

    }

  }

  private final ProcessVersions processVersions;

  private final OutfadedProcessVersions outfadedVersions;

  private final UnservedTasks unservedTasks;

  private final DeadHandlers deadHandlers;

  /**
   * What the application declared and what was really deployed - the second reading of
   * "older version" depends on it.
   */
  private final DeclaredBpmnProcesses declaredProcesses;

  /**
   * Where the elements of a held version which can produce a second token are judged.
   */
  private final ConcurrentTokenElementsOfHeldVersions concurrentTokenElements;

  /**
   * The adapters already reported as unable to answer, so a BPMS which cannot read old
   * models says so once per process instead of once per version.
   */
  private final Set<String> reportedAsUnableToTell = ConcurrentHashMap.newKeySet();

  /**
   * What every BPMN process of a workflow module can be served with, collected while the
   * processes are checked one by one - see {@link #reportDeadHandlers(String)}, whose
   * verdict belongs to the whole module.
   */
  private final java.util.Map<String, List<HeldVersions>> heldVersionsPerModule = new ConcurrentHashMap<>();

  /**
   * What one BPMS holds for one BPMN process: everything, what of it is worth serving,
   * and what the configuration faded out.
   *
   * @param adapterId The adapter ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param all Every version that BPMS holds, the one this boot deployed included
   * @param servable Those of them the configuration does not fade out
   * @param outfaded The rest
   */
  private record HeldVersions(
                              String adapterId,
                              String bpmnProcessId,
                              List<String> all,
                              List<String> servable,
                              List<String> outfaded) {
  }

  public DeployedProcessVersionsCheck(
      final ProcessVersions processVersions,
      final OutfadedProcessVersions outfadedVersions,
      final UnservedTasks unservedTasks,
      final DeadHandlers deadHandlers,
      final DeclaredBpmnProcesses declaredProcesses) {

    this(processVersions, outfadedVersions, unservedTasks, deadHandlers, declaredProcesses, null);

  }

  public DeployedProcessVersionsCheck(
      final ProcessVersions processVersions,
      final OutfadedProcessVersions outfadedVersions,
      final UnservedTasks unservedTasks,
      final DeadHandlers deadHandlers,
      final DeclaredBpmnProcesses declaredProcesses,
      final ConcurrentTokenElementsOfHeldVersions concurrentTokenElements) {

    this.processVersions = processVersions;
    this.outfadedVersions = outfadedVersions;
    this.unservedTasks = unservedTasks;
    this.deadHandlers = deadHandlers;
    this.declaredProcesses = declaredProcesses;
    this.concurrentTokenElements = concurrentTokenElements;

  }

  /**
   * Runs the check for one BPMN process, for every BPMS serving it.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @throws IllegalStateException If the configuration fades out the version this boot
   *           deployed, or if workflows run on an outfaded version and the policy is
   *           {@link OutfadedVersionsInUsePolicy#FAIL}
   */
  public void check(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var resolver = processVersions.resolverFor(workflowModuleId, bpmnProcessId);
    processVersions
        .registeredCatalogs(workflowModuleId, bpmnProcessId)
        .forEach(registered -> check(
            workflowModuleId,
            bpmnProcessId,
            registered.adapterId(),
            access(registered.catalog()),
            resolver));

  }

  /**
   * The check for ONE adapter - the entry point of the tests, which hand in their own
   * {@link ProcessVersionCatalogAccess}. What it finds about methods which never run is
   * remembered rather than reported: that verdict belongs to the whole workflow module
   * and is drawn by {@link #reportDeadHandlers(String)}.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param adapterId The adapter ID
   * @param catalog What that BPMS can tell about the process
   * @param resolver Resolves version tags of that process
   */
  public void check(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final ProcessVersionCatalogAccess catalog,
      final VersionRange.ProcessVersionResolver resolver) {

    final var deployed = processVersions.deployedVersion(adapterId, workflowModuleId, bpmnProcessId);
    // an id the application declares without bringing a model for it has no newer
    // version to compare against, so everything the BPMS holds under it is older
    final var everyHeldVersionIsOlder = (deployed == null) && (declaredProcesses != null) && declaredProcesses
        .isDeclaredWithoutDeployment(workflowModuleId, bpmnProcessId);
    if ((deployed == null) && !everyHeldVersionIsOlder) {
      // a BPMS counting no versions: there is no "older version" to speak of
      return;
    }
    if (deployed != null) {
      failIfDeployedVersionIsOutfaded(workflowModuleId, bpmnProcessId, adapterId, deployed, resolver);
    }

    final var known = catalog.deployedVersionsOf(workflowModuleId, bpmnProcessId);
    if ((known == null) || known.isEmpty()) {
      if (everyHeldVersionIsOlder) {
        reportDeclaredProcessNobodyHolds(workflowModuleId, bpmnProcessId, adapterId);
      }
      return;
    }
    rememberHeldVersions(workflowModuleId, bpmnProcessId, adapterId, known, deployed, resolver);
    // asking the BPMS how many workflows run on a version is a QUERY, and three of the
    // reports below want the same answer for the same version. Asked once per version
    // and per run of this check, and only for a version somebody actually asks about
    final var instanceCounts = new InstanceCounts(workflowModuleId, bpmnProcessId, catalog);
    final var olderVersions = everyHeldVersionIsOlder
        ? identifiersOf(known)
        : olderThan(known, deployed);
    reportWorkflowsOnOlderVersions(
        workflowModuleId, bpmnProcessId, adapterId, olderVersions, instanceCounts, catalog, everyHeldVersionIsOlder);
    final var concurrentTokensPerVersion = new java.util.LinkedHashMap<String, Collection<String>>();
    for (final var version : olderVersions) {
      if (outfadedVersions.isOutfaded(workflowModuleId, bpmnProcessId, adapterId, version, resolver)) {
        reportOutfadedVersionInUse(workflowModuleId, bpmnProcessId, adapterId, version, instanceCounts);
        continue;
      }
      rememberConcurrentTokenElements(
          workflowModuleId, bpmnProcessId, version, instanceCounts, catalog, concurrentTokensPerVersion);
      final var tasks = catalog.tasksOfVersion(workflowModuleId, bpmnProcessId, version);
      if (tasks == null) {
        reportUnableToReadModels(workflowModuleId, bpmnProcessId, adapterId);
        break;
      }
      final var unserved = unservedTasks.of(workflowModuleId, bpmnProcessId, version, tasks);
      if ((unserved == null) || unserved.isEmpty()) {
        continue;
      }
      reportUnservedTasks(workflowModuleId, bpmnProcessId, adapterId, version, unserved, instanceCounts);
    }
    if (concurrentTokenElements != null) {
      concurrentTokenElements.report(workflowModuleId, bpmnProcessId, concurrentTokensPerVersion);
    }

  }

  /**
   * Reads the elements of ONE held version which can put a second token into a running
   * workflow, and remembers them for the one report the whole BPMN process gets.
   * <p>
   * Only asked where workflows really run on that version: a version nobody is on can lose
   * nobody's update, and the count is the number the reports around this one already have.
   * A BPMS which cannot say how many workflows run on a version is asked anyway - "cannot
   * tell" is not "nobody", and losing an update silently is the one outcome worth a model
   * read.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param version The version identifier the BPMS reported
   * @param instanceCounts How many workflows run on a version, asked once per version
   * @param catalog What that BPMS can tell about the process
   * @param concurrentTokensPerVersion What was found so far, per version
   */
  private void rememberConcurrentTokenElements(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version,
      final InstanceCounts instanceCounts,
      final ProcessVersionCatalogAccess catalog,
      final java.util.Map<String, Collection<String>> concurrentTokensPerVersion) {

    if (concurrentTokenElements == null) {
      return;
    }
    final var running = instanceCounts.of(version);
    if ((running != null) && (running == 0)) {
      return;
    }
    final var elements = catalog.concurrentTokenElementsOfVersion(workflowModuleId, bpmnProcessId, version);
    if ((elements == null) || elements.isEmpty()) {
      return;
    }
    concurrentTokensPerVersion.put(version, elements);

  }

  /**
   * Says how many workflows still run on a version older than the one this boot
   * deployed, and what those workflows will not get.
   *
   * <h2>Why this is worth a line even when everything is served</h2>
   *
   * The rest of this check reports a DEFECT: a task definition nobody serves, a version
   * faded out while workflows are on it. This reports the normal case right after an
   * application was upgraded, where every task IS served and the workflows are simply
   * older than the model. Nothing is wrong, and something is still worth knowing: a
   * feature which an adapter attaches to the MODEL it deploys cannot reach a workflow
   * which was started before, for the rest of that workflow's life. The number falls to
   * zero on its own as those workflows end, which is exactly what makes it useful to an
   * operator on the day of an upgrade.
   *
   * <h2>Why the number is trustworthy</h2>
   *
   * An older version exists only where the deployed model DIFFERS from what was deployed
   * before, and an adapter rewrites a model only to add something. So the same rewrite
   * which produced the new version is what the older workflows lack, and where nothing
   * was rewritten there is no older version and nothing is missing. The two questions
   * have one answer, which is why counting the versions answers both.
   *
   * <p>
   * What the older workflows lack is BPMS-specific, so it is not spelled out here: the
   * adapter reports it through {@link ProcessVersionCatalogAccess}, and an adapter which
   * attaches its behaviour while parsing rather than while deploying answers nothing,
   * because for it nothing is missing.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param adapterId The adapter ID
   * @param olderVersions The versions older than the deployed one
   * @param instanceCounts How many workflows run on a version, asked once per version
   * @param catalog What that BPMS can tell about the process
   * @param nothingDeployedUnderThatId Whether the application declares this BPMN process
   *          without bringing a model for it, which is what a rename leaves behind
   */
  private void reportWorkflowsOnOlderVersions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final List<String> olderVersions,
      final InstanceCounts instanceCounts,
      final ProcessVersionCatalogAccess catalog,
      final boolean nothingDeployedUnderThatId) {

    if (olderVersions.isEmpty()) {
      return;
    }
    var total = 0L;
    var counted = false;
    for (final var version : olderVersions) {
      final var running = instanceCounts.of(version);
      if (running == null) {
        continue;
      }
      counted = true;
      total += running;
    }
    if (!counted || (total == 0)) {
      // a BPMS which cannot count says so elsewhere already, and a version nobody
      // runs on is not news
      return;
    }
    if (!reportedAsUnableToTell.add(adapterId
        + "|older|"
        + workflowModuleId
        + "|"
        + bpmnProcessId)) {
      return;
    }
    final var missing = catalog.whatOlderVersionsMiss(workflowModuleId, bpmnProcessId);
    final var whatThoseWorkflowsMiss = (missing == null) || missing.isBlank()
        ? ""
        : ": ".concat(missing);
    if (nothingDeployedUnderThatId) {
      log.info(
          """
              {} workflow(s) still run on BPMN process '{}' (workflow module '{}'), which this \
              application does not deploy any more - adapter '{}' holds {} version(s) of it: {}. They \
              keep being served because a @WorkflowService declares that id (secondaryBpmnProcesses), \
              which is how a renamed BPMN process stays served, so this is not a defect. Whatever a \
              newer model added reaches the version it was deployed as and no earlier one, so those \
              workflows never get it{}. The number falls to zero as they end, and it is what tells \
              you when the declaration and the methods serving it can go.""",
          total,
          bpmnProcessId,
          workflowModuleId,
          adapterId,
          olderVersions.size(),
          String.join(", ", olderVersions),
          whatThoseWorkflowsMiss);
      return;
    }
    log.info(
        """
            {} workflow(s) of BPMN process '{}' (workflow module '{}') still run on {} version(s) \
            older than the one adapter '{}' deployed during this boot: {}. They keep being served - \
            this is not a defect - but whatever this version added TO THE MODEL reaches the version \
            it deployed and no earlier one, so those workflows never get it{}. The number falls to \
            zero as they end, and it is what tells you when the difference is gone.""",
        total,
        bpmnProcessId,
        workflowModuleId,
        olderVersions.size(),
        adapterId,
        String.join(", ", olderVersions),
        whatThoseWorkflowsMiss);

  }

  /**
   * Reports a BPMN process id the application declares although the BPMS holds nothing
   * under it - not a failure: it is what an old id looks like once its last workflow
   * ended, and it is also what a typo looks like.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID nothing was deployed under
   * @param adapterId The adapter ID
   */
  private void reportDeclaredProcessNobodyHolds(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    if (!reportedAsUnableToTell.add(adapterId
        + "|nothing-held|"
        + workflowModuleId
        + "|"
        + bpmnProcessId)) {
      return;
    }
    log.warn(
        """
            A @WorkflowService of workflow module '{}' declares BPMN process '{}' \
            (secondaryBpmnProcesses), but this application deploys no model under that id and \
            adapter '{}' holds no version of it either - nothing this application does reaches that \
            id. Where the process was renamed, this is what the old id looks like once its last \
            workflow has ended: the declaration and the methods kept for it can go. Otherwise check \
            the spelling against the BPMN process ids this workflow module deploys: {}.""",
        workflowModuleId,
        bpmnProcessId,
        adapterId,
        deployedProcessIdsOf(workflowModuleId));

  }

  /**
   * The BPMN process ids of that workflow module a model WAS deployed under during this
   * boot - what a developer compares a declared id which reaches nothing against.
   */
  private String deployedProcessIdsOf(
      final String workflowModuleId) {

    final var deployed = declaredProcesses
        .deployedProcessesOf(workflowModuleId)
        .stream()
        .map("'%s'"::formatted)
        .collect(Collectors.joining(", "));
    return deployed.isEmpty()
        ? "none"
        : deployed;

  }

  /**
   * Remembers what one BPMS holds for one BPMN process, for the dead-handler report of the
   * whole workflow module. "Worth serving" is what the BPMS holds minus what the
   * configuration faded out, so fading out a version also tells the developer which methods
   * just became pointless - the code-side counterpart of
   * <code>outfaded-versions-in-use</code>, which speaks about running workflows only.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param adapterId The adapter ID
   * @param known The versions that BPMS holds
   * @param deployed The version this boot deployed, or <code>null</code> where nothing was
   *          deployed under that id
   * @param resolver Resolves version tags of that process
   */
  private void rememberHeldVersions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final List<DeployedProcessVersion> known,
      final String deployed,
      final VersionRange.ProcessVersionResolver resolver) {

    if (deadHandlers == null) {
      return;
    }
    final var heldVersions = known == null
        ? List.<String>of()
        : known
            .stream()
            .map(DeployedProcessVersion::version)
            .filter(java.util.Objects::nonNull)
            .toList();
    // the version this boot deployed is held even where the BPMS did not list it
    final var allHeld = (deployed == null) || heldVersions.contains(deployed)
        ? heldVersions
        : java.util.stream.Stream.concat(heldVersions.stream(), java.util.stream.Stream.of(deployed)).toList();
    final var servable = allHeld
        .stream()
        .filter(version -> !outfadedVersions.isOutfaded(workflowModuleId, bpmnProcessId, adapterId, version, resolver))
        .toList();
    final var outfaded = allHeld
        .stream()
        .filter(version -> !servable.contains(version))
        .toList();
    heldVersionsPerModule
        .computeIfAbsent(workflowModuleId, module -> new java.util.concurrent.CopyOnWriteArrayList<>())
        .add(new HeldVersions(adapterId, bpmnProcessId, allHeld, servable, outfaded));

  }

  /**
   * Reports the methods of a workflow module which serve no version worth serving - once
   * the versions of every BPMN process of that module were read, because that is what the
   * verdict needs.
   * <p>
   * A method is registered once per BPMN process its class declares, so a method which
   * serves no version of one process may well be the one kept for another. That is the
   * whole point of a declaration a renamed process leaves behind: the versions under the
   * old id are what those methods exist for, and calling them dead would send a developer
   * to remove exactly the code which keeps the running workflows alive. Which is why this
   * is one statement per module rather than one per process, and why the registry gets the
   * versions of all of them ({@link DeadHandlers}).
   *
   * @param workflowModuleId The workflow module whose processes were checked
   */
  public void reportDeadHandlers(
      final String workflowModuleId) {

    final var held = heldVersionsPerModule.remove(workflowModuleId);
    if ((held == null) || (deadHandlers == null)) {
      return;
    }
    final var servableVersionsByProcess = new java.util.LinkedHashMap<String, Collection<String>>();
    held
        .forEach(versions -> servableVersionsByProcess
            .merge(
                versions.bpmnProcessId(),
                versions.servable(),
                (
                    alreadyKnown,
                    ofAnotherAdapter) -> java.util.stream.Stream
                        .concat(alreadyKnown.stream(), ofAnotherAdapter.stream())
                        .distinct()
                        .toList()));
    held
        .forEach(versions -> deadHandlers
            .of(workflowModuleId, versions.bpmnProcessId(), servableVersionsByProcess)
            .stream()
            .filter(handler -> reportedAsUnableToTell.add(versions.adapterId()
                + "|dead|"
                + handler))
            .forEach(handler -> log.warn(
                """
                    The {} of BPMN process '{}' (workflow module '{}') matches no version adapter '{}' \
                    holds{} - the method never runs. Widen its version range, remove the method, or deploy \
                    a version it serves.""",
                handler,
                versions.bpmnProcessId(),
                workflowModuleId,
                versions.adapterId(),
                versions.outfaded().isEmpty()
                    ? " (held: %s)".formatted(String.join(", ", versions.all()))
                    : " (held: %s, of which %s %s faded out by '%s')".formatted(
                        String.join(", ", versions.all()),
                        String.join(", ", versions.outfaded()),
                        versions.outfaded().size() == 1
                            ? "is"
                            : "are",
                        OutfadedProcessVersions.propertyName(versions.adapterId())))));

  }

  /**
   * The version identifiers of what a BPMS holds, in deployment order.
   */
  private static List<String> identifiersOf(
      final List<DeployedProcessVersion> known) {

    return known
        .stream()
        .map(DeployedProcessVersion::version)
        .filter(java.util.Objects::nonNull)
        .toList();

  }

  /**
   * The versions the BPMS holds which are OLDER than the one this boot deployed. The
   * catalog reports them in deployment order, so "older" is "before it in that list";
   * a deployed version the catalog does not know at all (a query which failed, a BPMS
   * which does not list what it just accepted) leaves every other version to be
   * checked, which errs towards checking too much rather than too little.
   */
  private static List<String> olderThan(
      final List<DeployedProcessVersion> known,
      final String deployed) {

    final var identifiers = known
        .stream()
        .map(DeployedProcessVersion::version)
        .filter(java.util.Objects::nonNull)
        .toList();
    final var index = identifiers.indexOf(deployed);
    return index < 0
        ? identifiers.stream().filter(version -> !version.equals(deployed)).toList()
        : identifiers.subList(0, index);

  }

  private void failIfDeployedVersionIsOutfaded(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final String deployed,
      final VersionRange.ProcessVersionResolver resolver) {

    final var covering = outfadedVersions
        .specificationsFor(workflowModuleId, bpmnProcessId, adapterId)
        .stream()
        .filter(specification -> specification.matches(deployed, resolver))
        .map(VersionRange::toString)
        .toList();
    if (covering.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        """
            Version '%s' of BPMN process '%s' (workflow module '%s') is the version adapter '%s' \
            deployed during this boot, but the specification(s) %s of '%s' cover it! Fading out the \
            version an application just deployed would leave the process without a served version. \
            Narrow the specification (e.g. '<%s') or remove it."""
            .formatted(
                deployed,
                bpmnProcessId,
                workflowModuleId,
                adapterId,
                covering.stream().map("'%s'"::formatted).collect(Collectors.joining(", ")),
                OutfadedProcessVersions.propertyName(adapterId),
                deployed));

  }

  private void reportUnservedTasks(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final String version,
      final Collection<BpmnTaskSpec> unserved,
      final InstanceCounts instanceCounts) {

    final var definitions = unserved
        .stream()
        .map(task -> "'%s'".formatted(task.taskDefinition() == null
            ? task.activityId()
            : task.taskDefinition()))
        .distinct()
        .collect(Collectors.joining(", "));
    final var running = instanceCounts.of(version);
    final var remedy = """
        Add a @WorkflowTask method whose version range covers version '%s', or declare that version \
        obsolete by adding e.g. '%s' to '%s'."""
        .formatted(version, version, OutfadedProcessVersions.propertyName(adapterId));

    if ((running != null) && (running > 0)) {
      log.error(
          """
              {} workflow(s) still run on version '{}' of BPMN process '{}' (workflow module '{}', \
              adapter '{}'), whose task definition(s) {} are served by NO @WorkflowTask method of this \
              application - each of them will fail with an incident at its next such task! {}""",
          running,
          version,
          bpmnProcessId,
          workflowModuleId,
          adapterId,
          definitions,
          remedy);
      return;
    }
    log.warn(
        """
            Version '{}' of BPMN process '{}' (workflow module '{}') is still deployed at adapter '{}' \
            and its task definition(s) {} are served by NO @WorkflowTask method of this application{}. \
            {}""",
        version,
        bpmnProcessId,
        workflowModuleId,
        adapterId,
        definitions,
        running == null
            ? ", and this BPMS cannot say whether workflows still run on it"
            : ", no workflow runs on it right now",
        remedy);

  }

  private void reportOutfadedVersionInUse(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final String version,
      final InstanceCounts instanceCounts) {

    final var running = instanceCounts.of(version);
    if (running == null) {
      reportUnableToTellAboutInstances(workflowModuleId, bpmnProcessId, adapterId);
      return;
    }
    if (running == 0) {
      return;
    }
    final var message = """
        %d workflow(s) still run on version '%s' of BPMN process '%s' (workflow module '%s', adapter \
        '%s'), which '%s' fades out - this application does not serve that version any more, so each \
        of them will fail with an incident at its next task whose definition nobody serves! Complete \
        or migrate those workflows, or stop fading out that version. Set \
        'vanillabp.adapters.%s.outfaded-versions-in-use' to 'FAIL' to make this stop the application \
        instead of only reporting it."""
        .formatted(
            running,
            version,
            bpmnProcessId,
            workflowModuleId,
            adapterId,
            OutfadedProcessVersions.propertyName(adapterId),
            adapterId);
    if (outfadedVersions.policyFor(workflowModuleId, bpmnProcessId, adapterId) == OutfadedVersionsInUsePolicy.FAIL) {
      throw new IllegalStateException(message);
    }
    log.error(message);

  }

  private void reportUnableToReadModels(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    if (!reportedAsUnableToTell.add(adapterId
        + "|models|"
        + workflowModuleId
        + "|"
        + bpmnProcessId)) {
      return;
    }
    log.warn(
        """
            Adapter '{}' cannot read the models of the older versions of BPMN process '{}' (workflow \
            module '{}') its BPMS still holds, so VanillaBP cannot tell whether this application still \
            serves them - the adapter's own log says why. Workflows running on such a version fail with \
            an incident at a task no @WorkflowTask method serves, which is what this check exists to \
            report before it happens.""",
        adapterId,
        bpmnProcessId,
        workflowModuleId);

  }

  private void reportUnableToTellAboutInstances(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    if (!reportedAsUnableToTell.add(adapterId
        + "|instances|"
        + workflowModuleId
        + "|"
        + bpmnProcessId)) {
      return;
    }
    log.warn(
        """
            Adapter '{}' cannot say how many workflows of BPMN process '{}' (workflow module '{}') still \
            run on the versions '{}' fades out - the adapter's own log says why. The versions stay faded \
            out; workflows still running on one of them fail with an incident at a task no @WorkflowTask \
            method serves.""",
        adapterId,
        bpmnProcessId,
        workflowModuleId,
        OutfadedProcessVersions.propertyName(adapterId));

  }

  private static ProcessVersionCatalogAccess access(
      final io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog catalog) {

    return new ProcessVersionCatalogAccess() {

      @Override
      public List<DeployedProcessVersion> deployedVersionsOf(
          final String workflowModuleId,
          final String bpmnProcessId) {

        return catalog.deployedVersionsOf(workflowModuleId, bpmnProcessId);

      }

      @Override
      public Collection<BpmnTaskSpec> tasksOfVersion(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String version) {

        return catalog.tasksOfVersion(workflowModuleId, bpmnProcessId, version);

      }

      @Override
      public Long activeInstanceCountOf(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String version) {

        return catalog.activeInstanceCountOf(workflowModuleId, bpmnProcessId, version);

      }

      @Override
      public String whatOlderVersionsMiss(
          final String workflowModuleId,
          final String bpmnProcessId) {

        return catalog.whatOlderVersionsMiss(workflowModuleId, bpmnProcessId);

      }

      @Override
      public Collection<String> concurrentTokenElementsOfVersion(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String version) {

        return catalog.concurrentTokenElementsOfVersion(workflowModuleId, bpmnProcessId, version);

      }

    };

  }

  /**
   * The instance count of a version, asked at most ONCE per version and per run of this
   * check.
   * <p>
   * Three of the reports want the same number for the same version, and every one of
   * them is a query to the BPMS: a count over the engine's runtime table on Camunda 7, a
   * search on Camunda 8. Asking twice was waste which grew with the number of versions a
   * BPMS holds, and that number grows with every deployment which changes a model.
   * <p>
   * Lazy on purpose: a version nobody reports about is never asked for. <code>null</code>
   * is remembered like any other answer, because "this BPMS cannot say" does not become
   * true on a second attempt either.
   */
  private static final class InstanceCounts {

    private final String workflowModuleId;

    private final String bpmnProcessId;

    private final ProcessVersionCatalogAccess catalog;

    private final java.util.Map<String, java.util.Optional<Long>> counts = new java.util.HashMap<>();

    private InstanceCounts(
        final String workflowModuleId,
        final String bpmnProcessId,
        final ProcessVersionCatalogAccess catalog) {

      this.workflowModuleId = workflowModuleId;
      this.bpmnProcessId = bpmnProcessId;
      this.catalog = catalog;

    }

    private Long of(
        final String version) {

      return counts
          .computeIfAbsent(
              version,
              asked -> java.util.Optional
                  .ofNullable(catalog.activeInstanceCountOf(workflowModuleId, bpmnProcessId, asked)))
          .orElse(null);

    }

  }

}
