package io.vanillabp.integration.adapter.migration.workflowstart;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.workflowtask.VersionRange;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartResult;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec;
import io.vanillabp.integration.spi.TransactionRunner;

/**
 * The <code>&#64;WorkflowStartedByBpms</code> methods of the application per
 * (workflow module, BPMN process), and the start events the adapters reported for
 * those processes. Backs the adapter-facing
 * {@link io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker},
 * which the workflow-task registry implements by delegating here.
 */
public class BpmsInitiatedStarts {

  private static final Logger log = LoggerFactory.getLogger(BpmsInitiatedStarts.class);

  private record RegistryKey(
                             String workflowModuleId,
                             String bpmnProcessId) {
  }

  private static class RegistryEntry {

    private final List<BpmsInitiatedStartHandler> handlers = new LinkedList<>();

    /**
     * The start events reported by the adapters deploying this process. Several
     * adapters may report the same process (the migration case) - the union is
     * what the application may serve.
     */
    private final List<BpmsInitiatedStartSpec> startEvents = new LinkedList<>();

  }

  private final Map<RegistryKey, RegistryEntry> entries = new ConcurrentHashMap<>();

  /**
   * What the BPMS know about the deployed versions of the BPMN processes - owned by the
   * {@link io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry}
   * and shared, since all three annotations carry a <code>version</code> attribute.
   */
  private final io.vanillabp.integration.adapter.migration.workflowtask.ProcessVersions processVersions;

  /**
   * Which ids the application declares without a deployment - owned by the registry as
   * well. For such an id every version a BPMS holds is an older one, and the version
   * exemption below has to read it the way its twin in the registry does
   * ({@code WorkflowTaskRegistry#servesOnlyOlderVersions}).
   */
  private final io.vanillabp.integration.adapter.migration.workflowtask.DeclaredBpmnProcesses declaredProcesses;

  public BpmsInitiatedStarts(
      final io.vanillabp.integration.adapter.migration.workflowtask.ProcessVersions processVersions,
      final io.vanillabp.integration.adapter.migration.workflowtask.DeclaredBpmnProcesses declaredProcesses) {

    this.processVersions = processVersions;
    this.declaredProcesses = declaredProcesses;

  }

  /**
   * Scans a workflow service class and registers what it found. Called by the
   * platform integration at startup, once per (workflow service class, declared
   * BPMN process ID).
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowServiceClass The <code>&#64;WorkflowService</code> class
   * @param workflowAggregateClass The workflow-aggregate class
   * @param workflowServiceBean Supplies the bean instance of that class
   * @param inherited The range the <code>&#64;BpmnProcess</code> of this
   *          process declares, which a method naming none serves
   */
  public void registerWorkflowService(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<?> workflowServiceClass,
      final Class<?> workflowAggregateClass,
      final Supplier<Object> workflowServiceBean,
      final io.vanillabp.integration.adapter.migration.workflowtask.InheritedVersions inherited) {

    final var handlers = BpmsInitiatedStartScanner
        .scan(workflowServiceClass, workflowAggregateClass, workflowServiceBean, inherited);
    if (handlers.isEmpty()) {
      return;
    }
    final var entry = entries
        .computeIfAbsent(new RegistryKey(workflowModuleId, bpmnProcessId), key -> new RegistryEntry());
    synchronized (entry) {
      // one by one, so two methods of the SAME class serving one start event are
      // compared against each other as well
      handlers
          .forEach(handler -> {
            failOnDuplicateWiring(
                workflowModuleId,
                bpmnProcessId,
                entry,
                handler,
                io.vanillabp.integration.adapter.migration.workflowtask.VersionRange.NO_RESOLVER);
            entry.handlers.add(handler);
          });
    }

  }

  /**
   * Resolves the version tags the methods of the given workflow module name and checks
   * the version ranges naming a tag for overlaps - those could not be placed while
   * registering, since no BPMS had been asked about its versions at that point.
   *
   * @param workflowModuleId The workflow module ID
   */
  public void resolveProcessVersions(
      final String workflowModuleId) {

    entries
        .entrySet()
        .stream()
        .filter(entry -> entry.getKey().workflowModuleId().equals(workflowModuleId))
        .forEach(entry -> {
          final var bpmnProcessId = entry.getKey().bpmnProcessId();
          final var registryEntry = entry.getValue();
          if (registryEntry.handlers.stream().allMatch(handler -> handler.versionTags().isEmpty())) {
            return;
          }
          processVersions.warmUp(workflowModuleId, bpmnProcessId);
          final var resolver = processVersions.resolverFor(workflowModuleId, bpmnProcessId);
          synchronized (registryEntry) {
            registryEntry.handlers
                .forEach(handler -> handler
                    .versionTags()
                    .forEach(tag -> processVersions.reportUnknownVersionTag(
                        workflowModuleId,
                        bpmnProcessId,
                        tag,
                        "method '%s'%s"
                            .formatted(handler.describe(), handler.describeVersionOrigin()))));
            registryEntry.handlers
                .forEach(handler -> failOnDuplicateWiring(
                    workflowModuleId,
                    bpmnProcessId,
                    registryEntry,
                    handler,
                    resolver));
          }
        });

  }

  private static void failOnDuplicateWiring(
      final String workflowModuleId,
      final String bpmnProcessId,
      final RegistryEntry entry,
      final BpmsInitiatedStartHandler handler,
      final io.vanillabp.integration.adapter.migration.workflowtask.VersionRange.ProcessVersionResolver resolver) {

    final var duplicate = entry.handlers
        .stream()
        .filter(existing -> existing != handler)
        .filter(existing -> java.util.Objects.equals(existing.getStartEventId(), handler.getStartEventId()))
        // overlapping version ranges are ambiguous; disjoint ones are a legitimate
        // way to serve several process versions
        .filter(existing -> existing.overlapsVersions(handler, resolver))
        .findFirst();
    if (duplicate.isPresent()) {
      throw new IllegalStateException(
          """
              The @WorkflowStartedByBpms methods '%s' (version %s) and '%s' (version %s) both serve \
              %s of BPMN process '%s' of workflow module '%s'! Remove one of them, name the start \
              events they serve by @WorkflowStartedByBpms(id = ...) or distinguish them by version - \
              on the method by @WorkflowStartedByBpms(version = ...), or, where a whole class serves \
              one generation of the model, on the class by @BpmnProcess(version = ...)."""
              .formatted(
                  duplicate.get().describe(),
                  duplicate.get().describeVersionsWithOrigin(),
                  handler.describe(),
                  handler.describeVersionsWithOrigin(),
                  handler.describeWiring(),
                  bpmnProcessId,
                  workflowModuleId));
    }

  }

  /**
   * The @WorkflowStartedByBpms methods registered for that BPMN process, each with the
   * verdict whether it serves one of the given versions: the versions the BPMS holds, minus
   * the ones the configuration faded out. A method serving none of them anywhere in its
   * workflow module never runs, and the start says so - which of the two the registry
   * decides, since a method is registered once per BPMN process its class declares.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param servableVersions The versions worth serving
   * @param resolver Resolves version tags of that process
   * @return One verdict per registered method
   */
  public java.util.List<io.vanillabp.integration.adapter.migration.workflowtask.HandlerVersions> handlerVersions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final java.util.Collection<String> servableVersions,
      final VersionRange.ProcessVersionResolver resolver) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if (entry == null) {
      return java.util.List.of();
    }
    return entry.handlers
        .stream()
        .map(handler -> new io.vanillabp.integration.adapter.migration.workflowtask.HandlerVersions(
            handler.describe(), "@WorkflowStartedByBpms method '%s' (version %s)"
                .formatted(handler.describe(), handler.describeVersionsWithOrigin()), servableVersions.stream()
                    .anyMatch(version -> handler.matchesVersion(version, resolver))))
        .toList();

  }

  /**
   * Registers the start events an adapter reported while wiring a deployed BPMN
   * process and validates the application's methods against them.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param startEvents The BPMS-initiated start events of the process
   * @throws IllegalStateException If a method serves a process without such a start
   *           event, or names a start event the process does not have
   */
  public void validate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<BpmsInitiatedStartSpec> startEvents) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if (entry == null) {
      // no application method for this process: the core builds the aggregate on
      // its own, which is the whole point of the hook being optional
      return;
    }
    synchronized (entry) {
      startEvents
          .stream()
          .filter(spec -> entry.startEvents
              .stream()
              .noneMatch(known -> known.elementId().equals(spec.elementId())))
          .forEach(entry.startEvents::add);

      if (entry.startEvents.isEmpty()) {
        throw new IllegalStateException(
            """
                The @WorkflowStartedByBpms method(s) %s serve BPMN process '%s' of workflow module \
                '%s', but that process has no start event the BPMS fires on its own (timer, signal or \
                conditional)! Either the model is missing such a start event, or the method belongs \
                to another process - a workflow started by the application gets its aggregate from \
                ProcessService#startWorkflow."""
                .formatted(
                    describeHandlers(entry.handlers),
                    bpmnProcessId,
                    workflowModuleId));
      }

      entry.handlers
          .stream()
          .filter(handler -> handler.getStartEventId() != null)
          // A method kept for an OLDER version names a start event the
          // deployed model may not have any more - that is what it is for. Whether
          // such a version still exists is answered by the startup check, which
          // reports a method serving no held version as dead.
          .filter(handler -> !servesOnlyOlderVersions(workflowModuleId, bpmnProcessId, handler))
          .filter(handler -> entry.startEvents
              .stream()
              .noneMatch(spec -> spec.elementId().equals(handler.getStartEventId())))
          .findFirst()
          .ifPresent(handler -> {
            throw new IllegalStateException(
                """
                    The @WorkflowStartedByBpms method '%s' serves start event '%s' of BPMN process '%s' \
                    of workflow module '%s', but that process has no such start event fired by the \
                    BPMS! Its BPMS-initiated start events are: %s."""
                    .formatted(
                        handler.describe(),
                        handler.getStartEventId(),
                        bpmnProcessId,
                        workflowModuleId,
                        describeStartEvents(entry.startEvents)));
          });
    }

  }

  /**
   * Judges the <code>&#64;WorkflowStartedByBpms</code> methods of the BPMN process ids the
   * workflow module DECLARES without deploying a model for them, against the start events
   * of the versions the BPMS still holds under such an id.
   * <p>
   * Nothing wires such an id during a boot - the id a renamed BPMN process left behind
   * arrives with no model - so {@link #validate} is never called for it and its methods are
   * judged by nothing, while the BPMS may fire the old model's timer every day. The
   * question the judgement needs is
   * {@link io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog#startEventsOfVersion},
   * and this is where the answer is used.
   * <p>
   * Every finding is a warning naming the versions it was drawn from, never the end of a
   * boot: what is read here are models nobody can change any more, and a check reading them
   * says what it found instead of refusing - see decision 38 in the repository's
   * DECISIONS.md. Where a version cannot be read at all, this stays silent for that BPMS,
   * because a start event might be sitting in exactly the model which could not be read.
   *
   * @param workflowModuleId The workflow module which finished deploying
   */
  public void validateAgainstVersionsTheBpmsHolds(
      final String workflowModuleId) {

    if (declaredProcesses == null) {
      return;
    }
    entries
        .entrySet()
        .stream()
        .filter(entry -> entry.getKey().workflowModuleId().equals(workflowModuleId))
        .filter(entry -> declaredProcesses
            .isDeclaredWithoutDeployment(workflowModuleId, entry.getKey().bpmnProcessId()))
        .forEach(entry -> validateAgainstVersionsTheBpmsHolds(
            workflowModuleId,
            entry.getKey().bpmnProcessId(),
            entry.getValue()));

  }

  /**
   * The same for one declared-only BPMN process, once per BPMS answering for it: the
   * versions belong to one BPMS, so the message names the adapter which holds them.
   */
  private void validateAgainstVersionsTheBpmsHolds(
      final String workflowModuleId,
      final String bpmnProcessId,
      final RegistryEntry entry) {

    processVersions
        .registeredCatalogs(workflowModuleId, bpmnProcessId)
        .forEach(registered -> {
          final var heldVersions = registered
              .catalog()
              .deployedVersionsOf(workflowModuleId, bpmnProcessId);
          if ((heldVersions == null) || heldVersions.isEmpty()) {
            // an id nothing is held under is reported by the startup check for old
            // process versions, in its own words and once per adapter
            return;
          }
          final var versionsRead = new LinkedList<String>();
          final var startEvents = new LinkedList<BpmsInitiatedStartSpec>();
          for (final var held : heldVersions) {
            final var version = held.version();
            if (version == null) {
              continue;
            }
            final var eventsOfVersion = registered
                .catalog()
                .startEventsOfVersion(workflowModuleId, bpmnProcessId, version);
            if (eventsOfVersion == null) {
              // one model which cannot be read is enough to make every verdict about
              // this id a guess, so nothing is said about this BPMS at all
              return;
            }
            versionsRead.add(version);
            eventsOfVersion
                .stream()
                .filter(spec -> startEvents
                    .stream()
                    .noneMatch(known -> known.elementId().equals(spec.elementId())))
                .forEach(startEvents::add);
          }
          if (versionsRead.isEmpty()) {
            return;
          }
          reportStartEventsTheHeldVersionsLack(
              workflowModuleId, bpmnProcessId, registered.adapterId(), entry, versionsRead, startEvents);
        });

  }

  /**
   * Says what the methods of a declared-only id serve which the versions the BPMS holds do
   * not have - the whole process where no held version starts on its own, and the single
   * method otherwise.
   */
  private void reportStartEventsTheHeldVersionsLack(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final RegistryEntry entry,
      final List<String> versionsRead,
      final List<BpmsInitiatedStartSpec> startEvents) {

    synchronized (entry) {
      if (startEvents.isEmpty()) {
        log
            .warn(
                """
                    The @WorkflowStartedByBpms method(s) {} serve BPMN process '{}' of workflow module \
                    '{}', which this application declares without deploying a model for it, but none of \
                    the version(s) adapter '{}' still holds under that id ({}) has a start event the \
                    BPMS fires on its own (timer, signal or conditional) - those methods never run. \
                    Either the declared id is misspelled, and this workflow module deploys {}, or the \
                    methods belong to another process: a workflow started by the application gets its \
                    aggregate from ProcessService#startWorkflow.""",
                describeHandlers(entry.handlers),
                bpmnProcessId,
                workflowModuleId,
                adapterId,
                String.join(", ", versionsRead),
                deployedProcessIdsOf(workflowModuleId));
        return;
      }
      entry.handlers
          .stream()
          .filter(handler -> handler.getStartEventId() != null)
          .filter(handler -> startEvents
              .stream()
              .noneMatch(spec -> spec.elementId().equals(handler.getStartEventId())))
          .forEach(handler -> log
              .warn(
                  """
                      The @WorkflowStartedByBpms method '{}' serves start event '{}' of BPMN process '{}' \
                      of workflow module '{}', which this application declares without deploying a model \
                      for it, but no version adapter '{}' still holds under that id has such a start event \
                      fired by the BPMS - that method never runs. The BPMS-initiated start events of the \
                      version(s) {} are: {}. Correct the id against the model the BPMS holds, or remove \
                      the method once the workflows it was kept for have ended.""",
                  handler.describe(),
                  handler.getStartEventId(),
                  bpmnProcessId,
                  workflowModuleId,
                  adapterId,
                  String.join(", ", versionsRead),
                  describeStartEvents(startEvents)));
    }

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
   * Whether the method exists for versions OLDER than the one this boot deployed
   * - it then names an element of a model which is not the deployed one.
   * <p>
   * A BPMN process id the application declares WITHOUT bringing a model - the id a
   * renamed process left behind - is the same situation with a different boundary:
   * this boot deployed no version of it at all, so every version the BPMS holds is
   * what a method serving the id is kept for. The registry's twin of this method
   * ({@code WorkflowTaskRegistry#servesOnlyOlderVersions}) grew this branch first,
   * and without it here a method matching a held version of such an id would be
   * validated against the start events of a model which does not exist.
   */
  private boolean servesOnlyOlderVersions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final BpmsInitiatedStartHandler handler) {

    final var resolver = processVersions.resolverFor(workflowModuleId, bpmnProcessId);
    if ((declaredProcesses != null) && declaredProcesses.isDeclaredWithoutDeployment(workflowModuleId, bpmnProcessId)) {
      final var heldVersions = processVersions
          .registeredCatalogs(workflowModuleId, bpmnProcessId)
          .stream()
          .map(registered -> registered
              .catalog()
              .deployedVersionsOf(workflowModuleId, bpmnProcessId))
          .filter(java.util.Objects::nonNull)
          .flatMap(List::stream)
          .map(io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion::version)
          .filter(java.util.Objects::nonNull)
          .distinct()
          .toList();
      if (!heldVersions.isEmpty()) {
        return heldVersions
            .stream()
            .anyMatch(version -> handler.matchesVersion(version, resolver));
      }
      // nothing held and nothing to ask: unlike the registry's twin, this check may
      // run BEFORE the task wiring settled the difference between declared and
      // deployed - an id looking declared-only without any catalog is answered by
      // the strict path below, so a deployed process validated first stays as
      // strictly judged as it was
    }
    final var deployedVersions = processVersions
        .registeredCatalogs(workflowModuleId, bpmnProcessId)
        .stream()
        .map(registered -> processVersions.deployedVersion(registered.adapterId(), workflowModuleId, bpmnProcessId))
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
    if (deployedVersions.isEmpty()) {
      return false;
    }
    return deployedVersions
        .stream()
        .noneMatch(version -> handler.matchesVersion(version, resolver));

  }

  /**
   * Builds the workflow aggregate of a workflow the BPMS started on its own.
   *
   * @param <A> The workflow-aggregate type
   * @param processService The process service of the BPMN process
   * @param context The adapter's notification
   * @param transactionRunner The platform's transaction runner
   * @return The aggregate's ID and the variables the adapter writes back
   */
  public <A> BpmsInitiatedStartResult start(
      final MigrationProcessService<A> processService,
      final BpmsInitiatedStartContext context,
      final TransactionRunner transactionRunner) {

    final var entry = entries
        .get(
            new RegistryKey(processService.getWorkflowModuleId(), processService.getBpmnProcessId()));
    final var wired = entry == null
        ? List.<BpmsInitiatedStartHandler>of()
        : entry.handlers
            .stream()
            .filter(candidate -> candidate.matchesStartEvent(context.getStartEventId()))
            .toList();
    final var handler = wired
        .stream()
        .filter(candidate -> candidate.matchesVersion(
            context.getProcessVersion(),
            processVersions.resolverFor(
                processService.getWorkflowModuleId(),
                processService.getBpmnProcessId())))
        .findFirst()
        .orElse(null);
    if ((handler == null) && !wired.isEmpty()) {
      // the aggregate is built either way, but the method meant to initialize it does
      // not run - said out loud, because an empty aggregate looks like a defect later
      log
          .warn(
              "No @WorkflowStartedByBpms method of BPMN process '{}' (workflow module '{}') serves "
                  + "start event '{}' of process version '{}' - the aggregate is built without "
                  + "initialization. Methods wired to that start event: {}.{}",
              processService.getBpmnProcessId(),
              processService.getWorkflowModuleId(),
              context.getStartEventId(),
              context.getProcessVersion(),
              describeHandlers(wired),
              io.vanillabp.integration.adapter.migration.workflowtask.VersionRange
                  .noVersionReportedHint(
                      context.getProcessVersion(),
                      wired.stream().anyMatch(BpmsInitiatedStartHandler::inheritsVersions)));
    }

    final var result = BpmsInitiatedStartExecution.run(processService, handler, context, transactionRunner);
    // the BPMS just built this workflow through us - it holds it
    if (result != null) {
      processService.rememberWorkflowAdapter(result.workflowAggregateId(), context.getAdapterId());
    }
    return result;

  }

  private static String describeHandlers(
      final List<BpmsInitiatedStartHandler> handlers) {

    return handlers
        .stream()
        .map(handler -> "'%s' (%s)".formatted(handler.describe(), handler.describeWiring()))
        .collect(Collectors.joining(", "));

  }

  private static String describeStartEvents(
      final List<BpmsInitiatedStartSpec> startEvents) {

    return startEvents
        .stream()
        .map(spec -> "'%s' (%s)".formatted(spec.elementId(), spec.kind()))
        .collect(Collectors.joining(", "));

  }

}
