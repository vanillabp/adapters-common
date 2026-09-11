package io.vanillabp.integration.adapter.migration.scoping;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.migration.config.AdapterProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;

/**
 * THE implementation of the name-clash-avoidance model: resolving the
 * mode and composing the identifiers a BPMS sees. BPMS-neutral by design - an
 * adapter decides only WHERE to apply the results (its model, its commands).
 *
 * <h2>Resolution</h2>
 *
 * The mode is an ADAPTER-SCOPED property, so it is resolved most-specific-wins
 * across workflow &gt; workflow module &gt; adapter
 * ({@link MigrationAdapterProperties#resolveForAdapter}) - which is what allows two
 * adapter ids to carry DIFFERENT modes for the same workflow module, the basis of
 * the migration path documented on {@link NameClashAvoidance}. Without any
 * configuration the ADAPTER's default applies
 * ({@link AdapterDeploymentService#defaultNameClashAvoidance()}):
 * {@link NameClashAvoidance#BY_ADAPTER} - VanillaBP 1's behavior - for a BPMS which
 * isolates out of the box, {@link NameClashAvoidance#NONE} for one which has to be
 * set up for it first (Camunda 8 rejects tenant ids unless multi-tenancy is enabled).
 * A resolved {@link NameClashAvoidance#NONE} is therefore reported once per workflow
 * module and adapter by the adapter's own WARN
 * ({@link AdapterDeploymentService#warnAboutUnscopedIdentifiers(String, boolean)}) -
 * it protects nothing, and only the adapter knows what its BPMS offers instead.
 *
 * <h2>Composition and its inverse</h2>
 *
 * Outbound identifiers are always composed from KNOWN parts, and inbound ones are
 * stripped by matching a KNOWN prefix - never by searching for the first
 * {@link NameClashAvoidanceSupport#SEPARATOR}. The separator is therefore a
 * readability choice; what protects correctness is
 * {@link #validateNoCollidingProcessIds}.
 *
 * <h2>What the BPMS already held</h2>
 *
 * {@link #validateNoCollidingProcessIds} compares what is being deployed against itself,
 * which leaves out the identifiers somebody else put into the same BPMS earlier. Asking
 * about those is the adapter's work, because only it can query its BPMS, and
 * {@link #reportIdentifiersTheBpmsAlreadyHolds} is where the answer arrives. The core
 * composes the scoped form the adapter's plain identifiers end up as, resolves the mode
 * which produced it and words the warning, so a developer reads our side, their side and
 * the change which frees the name in one message. It stays a warning: the holder may be an
 * application which runs correctly.
 * <p>
 * Why this is the one place which resolves the mode and builds the scoped form is decision 9 in the
 * repository's DECISIONS.md.
 */
public class NameClashAvoidanceService implements NameClashAvoidanceSupport {

  private static final Logger log = LoggerFactory.getLogger(NameClashAvoidanceService.class);

  private final MigrationAdapterProperties properties;

  /**
   * The adapters' deployment services, resolved LAZILY: every adapter receives this
   * service, so it has to be constructible before any adapter exists. The result is
   * cached once it is non-empty (an empty resolution means the adapter beans were
   * not created yet).
   */
  private final Supplier<Collection<AdapterDeploymentService<?, ?>>> deploymentServices;

  private volatile Map<String, AdapterDeploymentService<?, ?>> deploymentServicesByAdapterId;

  /**
   * The (workflow module, adapter) pairs already reported as unscoped - the mode is
   * resolved on every runtime boundary, the WARN belongs to startup.
   */
  private final Set<String> unscopedReported = ConcurrentHashMap.newKeySet();

  /**
   * Which workflow module declares a scoped identifier, per adapter id and kind: filled
   * while the modules are deployed and read again when a version a BPMS still holds turns
   * up carrying the same name. The FIRST module which declared it keeps the entry, because
   * what a message needs is one other side to name, and a third module colliding is then
   * reported against the same one.
   */
  private final Map<String, String> moduleDeclaringScopedIdentifier = new ConcurrentHashMap<>();

  /**
   * Without the adapters' deployment services every adapter's default is
   * {@link NameClashAvoidance#BY_ADAPTER} and no adapter can report an unscoped
   * workflow module - for tests and for platforms not passing them.
   *
   * @param properties The VanillaBP configuration
   */
  public NameClashAvoidanceService(
      final MigrationAdapterProperties properties) {

    this(properties, List::of);

  }

  /**
   * @param properties The VanillaBP configuration
   * @param deploymentServices The adapters' deployment services, asked for their
   *          default mode and for reporting a workflow module whose identifiers are
   *          not scoped. Invoked on first use, never during construction.
   */
  public NameClashAvoidanceService(
      final MigrationAdapterProperties properties,
      final Supplier<Collection<AdapterDeploymentService<?, ?>>> deploymentServices) {

    this.properties = properties;
    this.deploymentServices = deploymentServices;

  }

  @Override
  public NameClashAvoidance modeFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    final var configured = properties != null
        ? properties.resolveForAdapter(
            workflowModuleId,
            bpmnProcessId,
            null,
            adapterId,
            AdapterProperties::getNameClashAvoidance)
        : null;
    final var mode = configured != null
        ? configured
        : defaultModeFor(adapterId);
    if (mode == NameClashAvoidance.NONE) {
      reportUnscopedIdentifiers(adapterId, workflowModuleId, configured == null);
    }
    return mode;

  }

  /**
   * The mode applying to the given adapter without any configuration - the adapter's
   * own default, {@link NameClashAvoidance#BY_ADAPTER} for an adapter which is
   * unknown here (see {@link #deploymentServices}).
   */
  private NameClashAvoidance defaultModeFor(
      final String adapterId) {

    final var deploymentService = deploymentServiceOf(adapterId);
    final var adapterDefault = deploymentService != null
        ? deploymentService.defaultNameClashAvoidance()
        : null;
    return adapterDefault != null
        ? adapterDefault
        : NameClashAvoidance.BY_ADAPTER;

  }

  /**
   * Lets the adapter report the workflow module as unscoped, once per workflow module
   * and adapter id. Resolving a mode without a workflow module (e.g. while comparing
   * two adapter instances) reports nothing - the per-module resolutions of the
   * deployment do.
   */
  private void reportUnscopedIdentifiers(
      final String adapterId,
      final String workflowModuleId,
      final boolean fromDefault) {

    if ((workflowModuleId == null) || (adapterId == null)) {
      return;
    }
    final var deploymentService = deploymentServiceOf(adapterId);
    if (deploymentService == null) {
      return;
    }
    if (!unscopedReported.add("%s@%s".formatted(workflowModuleId, adapterId))) {
      return;
    }
    deploymentService.warnAboutUnscopedIdentifiers(workflowModuleId, fromDefault);

  }

  private AdapterDeploymentService<?, ?> deploymentServiceOf(
      final String adapterId) {

    var byAdapterId = deploymentServicesByAdapterId;
    if (byAdapterId == null) {
      synchronized (this) {
        byAdapterId = deploymentServicesByAdapterId;
        if (byAdapterId == null) {
          final var collected = new LinkedHashMap<String, AdapterDeploymentService<?, ?>>();
          final var resolved = deploymentServices.get();
          if (resolved != null) {
            resolved
                .stream()
                .filter(java.util.Objects::nonNull)
                .forEach(service -> collected.putIfAbsent(service.getAdapterId(), service));
          }
          byAdapterId = collected;
          if (!collected.isEmpty()) {
            // an empty result means the adapters were not created yet - ask again
            deploymentServicesByAdapterId = collected;
          }
        }
      }
    }
    return byAdapterId.get(adapterId);

  }

  /**
   * Whether task definitions are additionally scoped by their BPMN process ID
   * (default <code>true</code>, see
   * {@link AdapterProperties#getPrefixTaskDefinitionsPerProcess()}).
   */
  private boolean scopeTaskDefinitionsPerProcess(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    if (properties == null) {
      return true;
    }
    final var configured = properties.resolveForAdapter(
        workflowModuleId,
        bpmnProcessId,
        null,
        adapterId,
        AdapterProperties::getPrefixTaskDefinitionsPerProcess);
    return (configured == null) || configured;

  }

  private boolean prefixes(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    return modeFor(workflowModuleId, bpmnProcessId, adapterId) == NameClashAvoidance.USE_PREFIX;

  }

  @Override
  public String scopedProcessId(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    if ((bpmnProcessId == null) || !prefixes(workflowModuleId, bpmnProcessId, adapterId)) {
      return bpmnProcessId;
    }
    return join(workflowModuleId, bpmnProcessId);

  }

  @Override
  public String scopedIdentifier(
      final String workflowModuleId,
      final String identifier,
      final String adapterId) {

    if ((identifier == null) || !prefixes(workflowModuleId, null, adapterId)) {
      return identifier;
    }
    return join(workflowModuleId, identifier);

  }

  @Override
  public String scopedTaskDefinition(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final String adapterId) {

    if ((taskDefinition == null) || !prefixes(workflowModuleId, bpmnProcessId, adapterId)) {
      return taskDefinition;
    }
    return scopeTaskDefinitionsPerProcess(workflowModuleId, bpmnProcessId, adapterId)
        ? join(workflowModuleId, bpmnProcessId, taskDefinition)
        : join(workflowModuleId, taskDefinition);

  }

  @Override
  public String plainProcessId(
      final String workflowModuleId,
      final String scopedBpmnProcessId,
      final String adapterId) {

    if ((scopedBpmnProcessId == null) || !prefixes(workflowModuleId, null, adapterId)) {
      return scopedBpmnProcessId;
    }
    return stripKnownPrefix(scopedBpmnProcessId, join(workflowModuleId, ""));

  }

  @Override
  public String plainIdentifier(
      final String workflowModuleId,
      final String scopedIdentifier,
      final String adapterId) {

    return plainProcessId(workflowModuleId, scopedIdentifier, adapterId);

  }

  @Override
  public String plainTaskDefinition(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedTaskDefinition,
      final String adapterId) {

    if ((scopedTaskDefinition == null) || !prefixes(workflowModuleId, bpmnProcessId, adapterId)) {
      return scopedTaskDefinition;
    }
    final var prefix = scopeTaskDefinitionsPerProcess(workflowModuleId, bpmnProcessId, adapterId)
        ? join(workflowModuleId, bpmnProcessId, "")
        : join(workflowModuleId, "");
    return stripKnownPrefix(scopedTaskDefinition, prefix);

  }

  @Override
  public void validateNoneNameClashStrategy(
      final String adapterId,
      final String byAdapterOnlyPropertyKey) {

    if ((byAdapterOnlyPropertyKey == null) || byAdapterOnlyPropertyKey.isBlank()) {
      return;
    }
    if (appliesByAdapterAnywhere(adapterId)) {
      return;
    }
    throw new IllegalStateException(
        """
            The property '%s' is configured, but nothing can use it: it takes effect only \
            where the name-clash-avoidance mode is 'by-adapter' - the BPMS' own isolation - \
            and the mode applying to adapter '%s' is %s. The two contradict each other, so \
            choose the one you mean:
              - remove '%s' if the workflow modules are not meant to be kept apart by the BPMS itself, or
              - vanillabp.adapters.%s.name-clash-avoidance: by-adapter   # let the BPMS keep them apart
            The mode may also be set per workflow module \
            (vanillabp.workflow-modules.<module>.adapters.%s.name-clash-avoidance), which is \
            enough to put the property to use for that module."""
            .formatted(
                byAdapterOnlyPropertyKey,
                adapterId,
                modesApplying(adapterId),
                byAdapterOnlyPropertyKey,
                adapterId,
                adapterId));

  }

  /**
   * Whether {@link NameClashAvoidance#BY_ADAPTER} applies anywhere for the given
   * adapter, i.e. whether any workflow module is kept apart by the BPMS itself:
   * configured at some level, or the adapter's default while at least one level leaves
   * the mode unconfigured.
   */
  private boolean appliesByAdapterAnywhere(
      final String adapterId) {

    if (properties == null) {
      return true; // nothing is known about the configuration, so nothing is claimed
    }
    if (!levelsConfiguring(adapterId, NameClashAvoidance.BY_ADAPTER).isEmpty()) {
      return true;
    }
    if (valueOfAdapterLevel(adapterId) != null) {
      return false; // configured, and it is not BY_ADAPTER (that was checked above)
    }
    if (defaultModeFor(adapterId) != NameClashAvoidance.BY_ADAPTER) {
      return false;
    }
    // the adapter's default applies wherever no level overrides it
    final var modules = properties.getWorkflowModules();
    return modules.isEmpty() || modules
        .values()
        .stream()
        .anyMatch(module -> valueOf(module.getAdapters(), adapterId) == null);

  }

  /**
   * The modes applying to the given adapter, for messages: the values configured at any
   * level plus the adapter's default where nothing is configured.
   */
  private String modesApplying(
      final String adapterId) {

    final var modes = new java.util.TreeSet<String>();
    for (final var mode : NameClashAvoidance.values()) {
      if (!levelsConfiguring(adapterId, mode).isEmpty()) {
        modes.add(nameOf(mode));
      }
    }
    if ((properties == null) || (valueOfAdapterLevel(adapterId) == null)) {
      modes.add(nameOf(defaultModeFor(adapterId)));
    }
    return modes
        .stream()
        .collect(Collectors.joining("' and '", "'", "'"));

  }

  private static String nameOf(
      final NameClashAvoidance mode) {

    return mode
        .name()
        .toLowerCase()
        .replace('_', '-');

  }

  @Override
  public void validateNativeIsolationSupported(
      final String adapterId,
      final String workflowModuleId,
      final String bpmsDescription) {

    if (workflowModuleId != null) {
      // the mode is resolvable per workflow module, so check the one being deployed
      if (modeFor(workflowModuleId, null, adapterId) != NameClashAvoidance.BY_ADAPTER) {
        return;
      }
      throw new IllegalStateException(
          """
              %s has no isolation mechanism of its own, so the name-clash-avoidance mode '%s' \
              cannot be served by adapter '%s' - but it applies to workflow module '%s'%s. Choose \
              explicitly:
                vanillabp.adapters.%s.name-clash-avoidance: use-prefix   # VanillaBP prefixes the identifiers
                vanillabp.adapters.%s.name-clash-avoidance: none         # your identifiers are unique already
              The same key may be set per workflow module and workflow \
              (vanillabp.workflow-modules.%s.adapters.%s.name-clash-avoidance)."""
              .formatted(
                  capitalize(bpmsDescription),
                  NameClashAvoidance.BY_ADAPTER.name().toLowerCase().replace('_', '-'),
                  adapterId,
                  workflowModuleId,
                  levelsConfiguring(adapterId, NameClashAvoidance.BY_ADAPTER).isEmpty()
                      ? " (nothing is configured, so the default applies)"
                      : "",
                  adapterId,
                  adapterId,
                  workflowModuleId,
                  adapterId));
    }

    final var levels = levelsConfiguring(adapterId, NameClashAvoidance.BY_ADAPTER);
    // BY_ADAPTER is the DEFAULT, so an adapter of a BPMS without isolation has to
    // report the levels where nothing is configured either - the developer has to
    // choose actively there
    final var unconfiguredLevels = unconfiguredLevels(adapterId);
    if (levels.isEmpty() && unconfiguredLevels.isEmpty()) {
      return;
    }
    final var affected = new LinkedList<String>(levels);
    affected.addAll(unconfiguredLevels);
    throw new IllegalStateException(
        """
            %s has no isolation mechanism of its own, so the name-clash-avoidance mode \
            '%s' cannot be served by adapter '%s'! Affected configuration: %s. Choose \
            explicitly:
              vanillabp.adapters.%s.name-clash-avoidance: use-prefix   # VanillaBP prefixes the identifiers
              vanillabp.adapters.%s.name-clash-avoidance: none         # your identifiers are unique already
            The same key may be set per workflow module and workflow \
            (vanillabp.workflow-modules.<module>.adapters.%s.name-clash-avoidance)."""
            .formatted(
                capitalize(bpmsDescription),
                NameClashAvoidance.BY_ADAPTER.name().toLowerCase().replace('_', '-'),
                adapterId,
                String.join(", ", affected),
                adapterId,
                adapterId,
                adapterId));

  }

  @Override
  public void validateNoCollidingProcessIds(
      final String adapterId,
      final Collection<DeployedProcess> deployedProcesses) {

    if (deployedProcesses == null) {
      return;
    }
    final var byScopedId = new LinkedHashMap<String, DeployedProcess>();
    final var collisions = new LinkedHashMap<String, LinkedList<DeployedProcess>>();
    for (final var deployed : deployedProcesses) {
      final var scoped = scopedProcessId(deployed.workflowModuleId(), deployed.bpmnProcessId(), adapterId);
      final var previous = byScopedId.putIfAbsent(scoped, deployed);
      if (previous == null) {
        continue;
      }
      if (previous.equals(deployed)) {
        continue; // the same process reported twice (several files, several adapters)
      }
      collisions
          .computeIfAbsent(scoped, key -> new LinkedList<>(java.util.List.of(previous)))
          .add(deployed);
    }
    if (collisions.isEmpty()) {
      return;
    }
    final var message = new StringBuilder(
        ("Different BPMN processes deployed to adapter '%s' end up under the SAME identifier! "
            + "Rename one of the colliding workflow modules or BPMN processes:")
            .formatted(adapterId));
    collisions.forEach((
        scoped,
        colliding) -> message.append(
            """

                - '%s' is produced by %s"""
                .formatted(
                    scoped,
                    colliding
                        .stream()
                        .map(process -> "BPMN process '%s' of workflow module '%s'"
                            .formatted(process.bpmnProcessId(), process.workflowModuleId()))
                        .collect(Collectors.joining(" and ")))));
    throw new IllegalStateException(message.toString());

  }

  @Override
  public void reportIdentifiersTheBpmsAlreadyHolds(
      final String adapterId,
      final String workflowModuleId,
      final Collection<IdentifierHeldElsewhere> found) {

    if (found == null) {
      return;
    }
    final var holdings = found
        .stream()
        .filter(held -> (held != null) && (held.kind() != null) && (held.plainIdentifier() != null))
        .toList();
    if (holdings.isEmpty()) {
      return;
    }

    // the fix depends on the mode, and the mode is resolved per workflow and per
    // workflow module, so ten findings may need two sentences or one - they are
    // collected while the findings are worded and written once each
    final var fixes = new LinkedHashSet<String>();
    final var describedHoldings = new LinkedList<String>();
    for (final var held : holdings) {
      final var processDecidingTheMode = processDecidingTheMode(held);
      final var mode = modeFor(workflowModuleId, processDecidingTheMode, adapterId);
      fixes.add(howToFree(mode, adapterId, workflowModuleId));
      describedHoldings.add(describe(held, workflowModuleId, adapterId, mode));
    }

    log
        .warn(
            """
                Adapter '{}' asked its BPMS which identifiers of workflow module '{}' it already \
                holds, and got {} of them back. Both sides deploy the same name, so \
                the BPMS alone decides which of them a start or a message reaches. \
                This does not stop the boot: whoever holds the name may be an application which runs \
                correctly, and ending this boot would not help it.
                What this application deploys, and who holds it already:{}
                What to change:{}""",
            adapterId,
            workflowModuleId,
            holdings.size(),
            asBullets(describedHoldings),
            asBullets(fixes));

  }

  /**
   * One block of the warning, so that ten findings stay readable: one line each, below
   * the sentence introducing them.
   */
  private static String asBullets(
      final Collection<String> lines) {

    return lines
        .stream()
        .collect(Collectors.joining("\n  - ", "\n  - ", ""));

  }

  /**
   * The BPMN process whose mode decides for the given finding: its own id where the
   * finding IS a process id, the process a task definition belongs to, and none for an
   * identifier the workflow module scopes alone.
   */
  private static String processDecidingTheMode(
      final IdentifierHeldElsewhere held) {

    return switch (held.kind()) {
      case BPMN_PROCESS_ID -> held.plainIdentifier();
      case TASK_DEFINITION -> held.bpmnProcessId();
      case MESSAGE_NAME, SIGNAL_NAME, ERROR_CODE, ESCALATION_CODE, DMN_DECISION_ID -> null;
    };

  }

  /**
   * One finding as the warning names it: what the application calls the identifier, what
   * the BPMS sees instead, where that form comes from, and what the adapter could find
   * out about the holder.
   */
  private String describe(
      final IdentifierHeldElsewhere held,
      final String workflowModuleId,
      final String adapterId,
      final NameClashAvoidance mode) {

    final var scopedForm = scopedFormOf(
        held.kind(),
        workflowModuleId,
        held.bpmnProcessId(),
        held.plainIdentifier(),
        adapterId);
    return """
        %s '%s'%s, which the BPMS %s (mode '%s', %s), is already held by %s%s"""
        .formatted(
            kindOf(held.kind()),
            held.plainIdentifier(),
            (held.kind() == ScopedIdentifierKind.TASK_DEFINITION) && (held.bpmnProcessId() != null)
                ? " of BPMN process '%s'".formatted(held.bpmnProcessId())
                : "",
            held.plainIdentifier().equals(scopedForm)
                ? "sees unchanged"
                : "sees as '%s'".formatted(scopedForm),
            nameOf(mode),
            whereTheModeComesFrom(workflowModuleId, processDecidingTheMode(held), adapterId),
            (held.heldBy() == null) || held.heldBy().isBlank()
                ? "a deployment this adapter cannot describe any further"
                : held.heldBy(),
            held.certainlyForeign()
                ? ""
                : ". VanillaBP cannot tell this from an earlier deployment of this application, so this line may be harmless");

  }

  /**
   * The scoped form the BPMS sees for one identifier of one workflow module - composed
   * here, because every caller hands over the plain identifier only.
   */
  private String scopedFormOf(
      final ScopedIdentifierKind kind,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String plainIdentifier,
      final String adapterId) {

    return switch (kind) {
      case BPMN_PROCESS_ID -> scopedProcessId(workflowModuleId, plainIdentifier, adapterId);
      // a task definition carries its BPMN process, so a caller knowing none leaves the
      // core with the module-wide form, which is also what an application switching the
      // process part off deploys
      case TASK_DEFINITION -> bpmnProcessId == null
          ? scopedIdentifier(workflowModuleId, plainIdentifier, adapterId)
          : scopedTaskDefinition(workflowModuleId, bpmnProcessId, plainIdentifier, adapterId);
      // a decision is called by several processes of a module, so it is scoped by the
      // module alone, exactly like a message or an error code
      case MESSAGE_NAME, SIGNAL_NAME, ERROR_CODE, ESCALATION_CODE, DMN_DECISION_ID -> scopedIdentifier(
          workflowModuleId,
          plainIdentifier,
          adapterId);
    };

  }

  /**
   * How the warning names a kind of identifier, in the words a developer sees in a
   * modeller rather than in the words of the enum.
   */
  private static String kindOf(
      final ScopedIdentifierKind kind) {

    return switch (kind) {
      case BPMN_PROCESS_ID -> "BPMN process id";
      case MESSAGE_NAME -> "message name";
      case SIGNAL_NAME -> "signal name";
      case ERROR_CODE -> "BPMN error code";
      case ESCALATION_CODE -> "escalation code";
      case TASK_DEFINITION -> "task definition";
      case DMN_DECISION_ID -> "DMN decision id";
    };

  }

  /**
   * The property key which produced the mode, so the developer edits the line which
   * decides rather than adding a second one somewhere else.
   */
  private String whereTheModeComesFrom(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    final var module = properties == null
        ? null
        : properties
            .getWorkflowModules()
            .get(workflowModuleId);
    if (module != null) {
      final var workflow = bpmnProcessId == null
          ? null
          : module
              .getWorkflows()
              .get(bpmnProcessId);
      if ((workflow != null) && (valueOf(workflow.getAdapters(), adapterId) != null)) {
        return "vanillabp.workflow-modules.%s.workflows.%s.adapters.%s.name-clash-avoidance"
            .formatted(workflowModuleId, bpmnProcessId, adapterId);
      }
      if (valueOf(module.getAdapters(), adapterId) != null) {
        return "vanillabp.workflow-modules.%s.adapters.%s.name-clash-avoidance".formatted(workflowModuleId, adapterId);
      }
    }
    if ((properties != null) && (valueOfAdapterLevel(adapterId) != null)) {
      return "vanillabp.adapters.%s.name-clash-avoidance".formatted(adapterId);
    }
    return "nothing is configured, so the default of adapter '%s' applies".formatted(adapterId);

  }

  /**
   * What frees the identifier again, per mode: each mode leaves a different way out, and
   * naming the wrong one sends the developer after a property which changes nothing.
   */
  private static String howToFree(
      final NameClashAvoidance mode,
      final String adapterId,
      final String workflowModuleId) {

    return switch (mode) {
      case USE_PREFIX -> """
          where the mode is 'use-prefix': the prefix carries the workflow module id already, so the \
          other side uses the same module id. Rename workflow module '%s', or rename the identifier \
          which clashes"""
          .formatted(workflowModuleId);
      case NONE -> """
          where the mode is 'none': nothing is scoped, so every application on this BPMS sees your \
          plain identifiers. Put the workflow module id in front of them with \
          'vanillabp.adapters.%s.name-clash-avoidance: use-prefix', or let the BPMS keep the modules \
          apart with 'vanillabp.adapters.%s.name-clash-avoidance: by-adapter'"""
          .formatted(adapterId, adapterId);
      case BY_ADAPTER -> """
          where the mode is 'by-adapter': the BPMS is supposed to keep the modules apart, so both \
          applications deploy into the same scope of it. Compare the scope of the two - where the \
          adapter uses a tenant for it, that is 'vanillabp.adapters.%s.tenant-id', and two \
          applications which name the same tenant are not kept apart by it"""
          .formatted(adapterId);
    };

  }

  @Override
  public void reportIdentifiersTheModelsDeclare(
      final String adapterId,
      final String workflowModuleId,
      final Collection<ModelIdentifier> declared) {

    if ((declared == null) || (workflowModuleId == null)) {
      return;
    }
    final var collisions = new LinkedList<String>();
    final var fixes = new LinkedHashSet<String>();
    final var modes = new LinkedHashSet<NameClashAvoidance>();
    for (final var identifier : declared) {
      if ((identifier == null) || (identifier.kind() == null) || (identifier.plainIdentifier() == null)) {
        continue;
      }
      final var scopedForm = scopedFormOf(
          identifier.kind(),
          workflowModuleId,
          null,
          identifier.plainIdentifier(),
          adapterId);
      final var moduleDeclaringItAlready = moduleDeclaringScopedIdentifier
          .putIfAbsent(declarationKey(adapterId, identifier.kind(), scopedForm), workflowModuleId);
      if ((moduleDeclaringItAlready == null) || moduleDeclaringItAlready.equals(workflowModuleId)) {
        // the first module declaring it, or this module declaring it in a second process -
        // the scope of such a name IS the workflow module, so sharing it inside one is
        // ordinary VanillaBP
        continue;
      }
      final var mode = modeFor(workflowModuleId, null, adapterId);
      modes.add(mode);
      fixes.add(howToFree(mode, adapterId, workflowModuleId));
      collisions
          .add(
              """
                  %s '%s' of workflow module '%s' and of workflow module '%s' both reach the BPMS as \
                  '%s' (mode '%s', %s)"""
                  .formatted(
                      kindOf(identifier.kind()),
                      identifier.plainIdentifier(),
                      moduleDeclaringItAlready,
                      workflowModuleId,
                      scopedForm,
                      nameOf(mode),
                      whereTheModeComesFrom(workflowModuleId, null, adapterId)));
    }
    if (collisions.isEmpty()) {
      return;
    }

    log
        .warn(
            """
                Two workflow modules of this application declare identifiers which adapter '{}' cannot \
                keep apart, so the BPMS sees one name where the application means two. A message \
                correlated for one module can reach a workflow of the other, and a signal broadcast \
                reaches both. The deployment is not stopped for it, because both models stay as they \
                are and two modules may share a name on purpose.{}
                What collides:{}
                What to change:{}""",
            adapterId,
            whatTheBpmsOwnIsolationHides(modes),
            asBullets(collisions),
            asBullets(fixes));

  }

  @Override
  public void reportIdentifiersOfHeldVersion(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version,
      final Long activeWorkflows,
      final Collection<ModelIdentifier> declared) {

    if ((declared == null) || (workflowModuleId == null)) {
      return;
    }
    final var shared = new LinkedList<String>();
    final var fixes = new LinkedHashSet<String>();
    final var modes = new LinkedHashSet<NameClashAvoidance>();
    for (final var identifier : declared) {
      if ((identifier == null) || (identifier.kind() == null) || (identifier.plainIdentifier() == null)) {
        continue;
      }
      final var scopedForm = scopedFormOf(
          identifier.kind(),
          workflowModuleId,
          bpmnProcessId,
          identifier.plainIdentifier(),
          adapterId);
      final var moduleDeployingItNow = moduleDeclaringScopedIdentifier
          .get(declarationKey(adapterId, identifier.kind(), scopedForm));
      if ((moduleDeployingItNow == null) || moduleDeployingItNow.equals(workflowModuleId)) {
        // nothing deploys that name today, or the module which does is the one the held
        // version belongs to, which is the same model carried forward rather than a clash
        continue;
      }
      final var mode = modeFor(moduleDeployingItNow, null, adapterId);
      modes.add(mode);
      fixes.add(howToFree(mode, adapterId, moduleDeployingItNow));
      shared
          .add(
              """
                  %s '%s', which the BPMS sees as '%s' (mode '%s', %s), is declared by workflow module \
                  '%s' of this deployment as well"""
                  .formatted(
                      kindOf(identifier.kind()),
                      identifier.plainIdentifier(),
                      scopedForm,
                      nameOf(mode),
                      whereTheModeComesFrom(moduleDeployingItNow, null, adapterId),
                      moduleDeployingItNow));
    }
    if (shared.isEmpty()) {
      return;
    }

    log
        .warn(
            """
                Version {} of BPMN process '{}' of workflow module '{}', which adapter '{}' still \
                holds{}, declares identifiers another workflow module of this deployment uses. The BPMS \
                cannot tell the two apart, so a message or a signal meant for one of them can reach the \
                other. Nobody can change the held model any more, so the name has to move on the side \
                being deployed - or the modules have to be scoped apart.{}
                What the held version shares:{}
                What to change:{}""",
            version,
            bpmnProcessId,
            workflowModuleId,
            adapterId,
            workflowsRunningOn(activeWorkflows),
            whatTheBpmsOwnIsolationHides(modes),
            asBullets(shared),
            asBullets(fixes));

  }

  /**
   * How urgent a held version's finding is, in the words the count allows: a version
   * carrying workflows is the one somebody has to act on, and a BPMS which cannot count
   * them says that instead of a number.
   */
  private static String workflowsRunningOn(
      final Long activeWorkflows) {

    if (activeWorkflows == null) {
      return " and which this BPMS cannot count the workflows of";
    }
    if (activeWorkflows == 0L) {
      return " with no workflow left on it";
    }
    return activeWorkflows == 1L
        ? " with one workflow still running on it"
        : " with %d workflows still running on it".formatted(activeWorkflows);

  }

  /**
   * What a finding cannot claim where the BPMS itself is supposed to keep the workflow
   * modules apart: whether it really does. The core resolves the mode and composes the
   * prefixes, but an isolation mechanism of a BPMS is the adapter's knowledge (a tenant, a
   * namespace, a database of its own), so under that mode the line is a question rather
   * than a verdict - and a reader who cannot see which of the two they got learns to ignore
   * the whole message.
   */
  private static String whatTheBpmsOwnIsolationHides(
      final Collection<NameClashAvoidance> modes) {

    return modes.contains(NameClashAvoidance.BY_ADAPTER)
        ? """
             Under 'by-adapter' it is the BPMS which is supposed to keep the two apart, and \
            VanillaBP cannot see whether it does: one scope configured for the whole adapter - a \
            tenant id, on the Camunda adapters - puts both workflow modules into it, while a scope \
            per workflow module keeps them apart and makes this harmless."""
        : "";

  }

  /**
   * The key the module declaring an identifier is remembered under. The kind is part of
   * it because a BPMS keeps the kinds apart: a message name equal to an error code
   * collides with nothing.
   */
  private static String declarationKey(
      final String adapterId,
      final ScopedIdentifierKind kind,
      final String scopedIdentifier) {

    return "%s|%s|%s".formatted(adapterId, kind, scopedIdentifier);

  }

  /**
   * The configuration levels which explicitly configure the given mode for the
   * given adapter - used for guiding messages.
   */
  private Collection<String> levelsConfiguring(
      final String adapterId,
      final NameClashAvoidance mode) {

    final var levels = new LinkedList<String>();
    if (properties == null) {
      return levels;
    }
    if (mode == valueOfAdapterLevel(adapterId)) {
      levels.add("vanillabp.adapters.%s".formatted(adapterId));
    }
    properties
        .getWorkflowModules()
        .forEach((
            moduleId,
            module) -> {
          if (mode == valueOf(module.getAdapters(), adapterId)) {
            levels.add("vanillabp.workflow-modules.%s.adapters.%s".formatted(moduleId, adapterId));
          }
          module
              .getWorkflows()
              .forEach((
                  workflowId,
                  workflow) -> {
                if (mode == valueOf(workflow.getAdapters(), adapterId)) {
                  levels.add(
                      "vanillabp.workflow-modules.%s.workflows.%s.adapters.%s"
                          .formatted(moduleId, workflowId, adapterId));
                }
              });
        });
    return levels;

  }

  /**
   * Whether the adapter has NO mode configured at all AND defaults to
   * {@link NameClashAvoidance#BY_ADAPTER} - which an adapter without native isolation
   * cannot serve either. An adapter defaulting to another mode is unaffected.
   */
  private Collection<String> unconfiguredLevels(
      final String adapterId) {

    if ((properties == null) || (valueOfAdapterLevel(adapterId) != null) || (defaultModeFor(
        adapterId) != NameClashAvoidance.BY_ADAPTER)) {
      return java.util.List.of();
    }
    return java.util.List.of("vanillabp.adapters.%s (nothing configured, the default applies)".formatted(adapterId));

  }

  private NameClashAvoidance valueOfAdapterLevel(
      final String adapterId) {

    final var adapter = properties
        .getAdapters()
        .get(adapterId);
    return adapter != null
        ? adapter.getNameClashAvoidance()
        : null;

  }

  private static NameClashAvoidance valueOf(
      final java.util.Map<String, ? extends AdapterProperties> adaptersOfLevel,
      final String adapterId) {

    if (adaptersOfLevel == null) {
      return null;
    }
    final var adapter = adaptersOfLevel.get(adapterId);
    return adapter != null
        ? adapter.getNameClashAvoidance()
        : null;

  }

  private static String join(
      final String... parts) {

    return String.join(SEPARATOR, parts);

  }

  /**
   * Strips the given prefix if - and only if - the identifier carries it.
   */
  private static String stripKnownPrefix(
      final String identifier,
      final String prefix) {

    return identifier.startsWith(prefix)
        ? identifier.substring(prefix.length())
        : identifier;

  }

  private static String capitalize(
      final String text) {

    return (text == null) || text.isEmpty()
        ? String.valueOf(text)
        : Character.toUpperCase(text.charAt(0)) + text.substring(1);

  }

}
