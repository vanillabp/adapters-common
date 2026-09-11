package io.vanillabp.integration.adapter.spi;

import java.util.Collection;

/**
 * Builds the identifiers a BPMS sees, according to the workflow module's
 * {@link NameClashAvoidance} mode. Implemented ONCE by the core (so every adapter
 * scopes identically) and handed to every adapter by the platform integration.
 * <p>
 * An adapter uses it in two places:
 * <ol>
 * <li>in <code>prepareBpmn</code>, to rewrite the identifiers of its BPMN model -
 * only the adapter knows its model type;</li>
 * <li>at every runtime boundary, to translate identifiers on their way to the BPMS
 * (starting a workflow, correlating a message, throwing a BPMN error, querying the
 * viewer API) and on their way back (finding the
 * <code>&#64;WorkflowTask</code> handler of a delivered task).</li>
 * </ol>
 * In the modes {@link NameClashAvoidance#NONE} and
 * {@link NameClashAvoidance#BY_ADAPTER} every <code>scoped*</code> method returns
 * its input unchanged, so an adapter may call them unconditionally.
 * <p>
 * <b>Never build the strings yourself:</b> the separator and the composition are
 * the core's business, and the startup validation of colliding identifiers relies
 * on it. Where the support itself may be absent - a unit test, a component built
 * without a platform around it - the STATIC methods of the same names take it as
 * their first argument and answer the plain identifier for a <code>null</code> one,
 * so nobody writes that rule again.
 * <p>
 * Why scoping happens at the BPMS boundary and nowhere else is decision 9 in the repository's
 * DECISIONS.md.
 */
public interface NameClashAvoidanceSupport {

  /**
   * The separator between a prefix and the identifier it scopes. Two underscores:
   * legal in an XML {@code NCName} (so BPMN element ids may carry it, unlike e.g.
   * <code>#</code>) and unlikely to appear in an identifier by accident.
   */
  String SEPARATOR = "__";

  /**
   * The mode configured for the given workflow module (and, if given, workflow) of
   * the given adapter - the most specific configured value wins (workflow >
   * workflow module > adapter). Without any configuration the adapter's own default
   * applies ({@link AdapterDeploymentService#defaultNameClashAvoidance()}).
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID or <code>null</code> to resolve the
   *          module's mode
   * @param adapterId The adapter ID
   * @return The mode, never <code>null</code>
   */
  NameClashAvoidance modeFor(
      String workflowModuleId,
      String bpmnProcessId,
      String adapterId);

  /**
   * The BPMN process ID as the BPMS knows it.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param adapterId The adapter ID
   * @return The scoped ID, or the plain one if the mode is not
   *         {@link NameClashAvoidance#USE_PREFIX}
   */
  String scopedProcessId(
      String workflowModuleId,
      String bpmnProcessId,
      String adapterId);

  /**
   * An identifier scoped by the workflow module alone - message names, signal
   * names, error codes and escalation codes.
   *
   * @param workflowModuleId The workflow module ID
   * @param identifier The plain identifier (may be <code>null</code>)
   * @param adapterId The adapter ID
   * @return The scoped identifier, or the plain one if the mode is not
   *         {@link NameClashAvoidance#USE_PREFIX}
   */
  String scopedIdentifier(
      String workflowModuleId,
      String identifier,
      String adapterId);

  /**
   * A task definition, scoped by the workflow module AND - unless the application
   * switched it off
   * (<code>prefix-task-definitions-per-process: false</code>) - by the BPMN process
   * ID. Reusing one task implementation across processes is an anti-pattern, so
   * scoping per process is the default.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param taskDefinition The plain task definition (may be <code>null</code>)
   * @param adapterId The adapter ID
   * @return The scoped task definition, or the plain one if the mode is not
   *         {@link NameClashAvoidance#USE_PREFIX}
   */
  String scopedTaskDefinition(
      String workflowModuleId,
      String bpmnProcessId,
      String taskDefinition,
      String adapterId);

  /**
   * The inverse of {@link #scopedProcessId}: the plain BPMN process ID of what the
   * BPMS reported. Strips a KNOWN prefix (never "everything up to the first
   * separator"), so an identifier which does not carry the expected prefix is
   * returned unchanged.
   *
   * @param workflowModuleId The workflow module ID
   * @param scopedBpmnProcessId The ID as the BPMS knows it
   * @param adapterId The adapter ID
   * @return The plain BPMN process ID
   */
  String plainProcessId(
      String workflowModuleId,
      String scopedBpmnProcessId,
      String adapterId);

  /**
   * The inverse of {@link #scopedIdentifier}.
   *
   * @param workflowModuleId The workflow module ID
   * @param scopedIdentifier The identifier as the BPMS knows it
   * @param adapterId The adapter ID
   * @return The plain identifier
   */
  String plainIdentifier(
      String workflowModuleId,
      String scopedIdentifier,
      String adapterId);

  /**
   * The inverse of {@link #scopedTaskDefinition}. The BPMN process ID is needed
   * because task definitions are scoped per process by default; pass the process
   * the task belongs to.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param scopedTaskDefinition The task definition as the BPMS knows it
   * @param adapterId The adapter ID
   * @return The plain task definition
   */
  String plainTaskDefinition(
      String workflowModuleId,
      String bpmnProcessId,
      String scopedTaskDefinition,
      String adapterId);

  /**
   * {@link #scopedProcessId} where the support may be absent - the shape a caller
   * holding it as a field needs, and the one place the rule "no scoping, plain
   * identifier" is written down.
   * <p>
   * An adapter has the support for every adapter id the platform registered, so
   * <code>null</code> is what a unit test hands in and what a component built without a
   * platform around it holds. Answering with the plain identifier is not a fallback but
   * the correct answer: without a mode there is nothing to prefix by, which is what
   * {@link NameClashAvoidance#NONE} and {@link NameClashAvoidance#BY_ADAPTER} answer as
   * well. Writing the check per call site is how two of them end up disagreeing, and a
   * disagreement here is a query which finds nothing.
   *
   * @param scoping The core's name-clash-avoidance support, or <code>null</code>
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param adapterId The adapter ID
   * @return The scoped ID, or the plain one
   */
  static String scopedProcessId(
      final NameClashAvoidanceSupport scoping,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    return scoping == null
        ? bpmnProcessId
        : scoping.scopedProcessId(workflowModuleId, bpmnProcessId, adapterId);

  }

  /**
   * {@link #scopedIdentifier} where the support may be absent - see
   * {@link #scopedProcessId(NameClashAvoidanceSupport, String, String, String)}.
   *
   * @param scoping The core's name-clash-avoidance support, or <code>null</code>
   * @param workflowModuleId The workflow module ID
   * @param identifier The plain identifier (may be <code>null</code>)
   * @param adapterId The adapter ID
   * @return The scoped identifier, or the plain one
   */
  static String scopedIdentifier(
      final NameClashAvoidanceSupport scoping,
      final String workflowModuleId,
      final String identifier,
      final String adapterId) {

    return scoping == null
        ? identifier
        : scoping.scopedIdentifier(workflowModuleId, identifier, adapterId);

  }

  /**
   * {@link #scopedTaskDefinition} where the support may be absent - see
   * {@link #scopedProcessId(NameClashAvoidanceSupport, String, String, String)}.
   *
   * @param scoping The core's name-clash-avoidance support, or <code>null</code>
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param taskDefinition The plain task definition (may be <code>null</code>)
   * @param adapterId The adapter ID
   * @return The scoped task definition, or the plain one
   */
  static String scopedTaskDefinition(
      final NameClashAvoidanceSupport scoping,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final String adapterId) {

    return scoping == null
        ? taskDefinition
        : scoping.scopedTaskDefinition(workflowModuleId, bpmnProcessId, taskDefinition, adapterId);

  }

  /**
   * {@link #plainProcessId} where the support may be absent - see
   * {@link #scopedProcessId(NameClashAvoidanceSupport, String, String, String)}.
   *
   * @param scoping The core's name-clash-avoidance support, or <code>null</code>
   * @param workflowModuleId The workflow module ID
   * @param scopedBpmnProcessId The ID as the BPMS knows it
   * @param adapterId The adapter ID
   * @return The plain BPMN process ID
   */
  static String plainProcessId(
      final NameClashAvoidanceSupport scoping,
      final String workflowModuleId,
      final String scopedBpmnProcessId,
      final String adapterId) {

    return scoping == null
        ? scopedBpmnProcessId
        : scoping.plainProcessId(workflowModuleId, scopedBpmnProcessId, adapterId);

  }

  /**
   * {@link #plainIdentifier} where the support may be absent - see
   * {@link #scopedProcessId(NameClashAvoidanceSupport, String, String, String)}.
   *
   * @param scoping The core's name-clash-avoidance support, or <code>null</code>
   * @param workflowModuleId The workflow module ID
   * @param scopedIdentifier The identifier as the BPMS knows it
   * @param adapterId The adapter ID
   * @return The plain identifier
   */
  static String plainIdentifier(
      final NameClashAvoidanceSupport scoping,
      final String workflowModuleId,
      final String scopedIdentifier,
      final String adapterId) {

    return scoping == null
        ? scopedIdentifier
        : scoping.plainIdentifier(workflowModuleId, scopedIdentifier, adapterId);

  }

  /**
   * {@link #plainTaskDefinition} where the support may be absent - see
   * {@link #scopedProcessId(NameClashAvoidanceSupport, String, String, String)}.
   *
   * @param scoping The core's name-clash-avoidance support, or <code>null</code>
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param scopedTaskDefinition The task definition as the BPMS knows it
   * @param adapterId The adapter ID
   * @return The plain task definition
   */
  static String plainTaskDefinition(
      final NameClashAvoidanceSupport scoping,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedTaskDefinition,
      final String adapterId) {

    return scoping == null
        ? scopedTaskDefinition
        : scoping.plainTaskDefinition(workflowModuleId, bpmnProcessId, scopedTaskDefinition, adapterId);

  }

  /**
   * Fails with a guiding message if the adapter carries configuration which only
   * {@link NameClashAvoidance#BY_ADAPTER} could honor, although BY_ADAPTER applies at
   * no level of this adapter: the BPMS' own isolation is never asked for, so the
   * setting has no effect and the two contradict each other. Either the setting is
   * meant and the mode has to say so, or the mode is meant and the setting is dead
   * configuration - silently ignoring it is the one outcome nobody asked for.
   * <p>
   * WHICH setting that is belongs to the ADAPTER, because the isolation mechanism does
   * (a tenant, a namespace, a database of its own - the core knows none of them). The
   * adapter decides that something is configured and hands over the property key it
   * would have to ignore; the core answers which modes apply and how to reconcile them.
   * Called while deploying (i.e. at startup), before anything reaches the BPMS.
   *
   * @param adapterId The adapter ID
   * @param byAdapterOnlyPropertyKey The full property key of the configured setting
   *          which only BY_ADAPTER could use, e.g.
   *          <code>vanillabp.adapters.myengine.tenant-id</code> - the message tells the
   *          developer to remove exactly this one. <code>null</code>/blank checks
   *          nothing
   * @throws IllegalStateException Naming that property, the modes which apply and both
   *           ways out
   */
  void validateNoneNameClashStrategy(
      String adapterId,
      String byAdapterOnlyPropertyKey);

  /**
   * Fails with a guiding message if the mode resolved for the given workflow module
   * is {@link NameClashAvoidance#BY_ADAPTER} although the adapter's BPMS offers no
   * isolation mechanism of its own. Called by such an adapter while DEPLOYING a
   * workflow module (i.e. at startup) - the alternative would be silently deploying
   * every workflow module into one scope.
   * <p>
   * Note that {@link NameClashAvoidance#BY_ADAPTER} is the DEFAULT, so an adapter
   * without native isolation also fails when nothing is configured at all: the
   * developer has to choose actively.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module being deployed, or <code>null</code>
   *          to check every configured level of this adapter
   * @param bpmsDescription How the BPMS is named in the message, e.g. "the
   *          Process-Engine-API"
   * @throws IllegalStateException Naming the workflow module respectively the
   *           configuration levels, and the alternatives
   */
  void validateNativeIsolationSupported(
      String adapterId,
      String workflowModuleId,
      String bpmsDescription);

  /**
   * Fails with a guiding message if two of the given (workflow module, BPMN process
   * ID) pairs produce the SAME scoped process ID - the only way prefixing could
   * mix two workflows up. Called by the adapter once it knows the deployed
   * processes (the BPMN has to be read first).
   *
   * @param adapterId The adapter ID
   * @param deployedProcesses The (workflow module ID, BPMN process ID) pairs
   *          deployed to this adapter
   * @throws IllegalStateException Naming the colliding pairs and the fix
   */
  void validateNoCollidingProcessIds(
      String adapterId,
      Collection<DeployedProcess> deployedProcesses);

  /**
   * One deployed BPMN process, used by {@link #validateNoCollidingProcessIds}.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   */
  record DeployedProcess(
                         String workflowModuleId,
                         String bpmnProcessId) {
  }

  /**
   * Warns about identifiers of the given workflow module which the BPMS ALREADY held
   * before this deployment, found by the adapter asking its BPMS. Called while
   * deploying, so at startup; nothing reads the result at runtime.
   * <p>
   * This never fails the boot. An identifier held elsewhere is a warning, because the
   * deployment on the other side may belong to another application which is running
   * correctly, and ending this boot would not help it. That rule is decision 38 in the
   * repository's DECISIONS.md.
   * <p>
   * Hand over the PLAIN identifiers. The core resolves the mode and composes the scoped
   * form the BPMS sees, so the separator and the composition stay in the one place which
   * owns them (decision 9 in the repository's DECISIONS.md), and the warning can name
   * both forms plus the property which produced them.
   * <p>
   * An adapter which cannot ask its BPMS calls nothing at all. Silence means "not asked"
   * everywhere in this SPI and it needs no value of its own, because the core forms no
   * verdict here: it reports what an adapter found and says for each finding how sure the
   * adapter is about it.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module whose identifiers were asked about
   * @param found What the BPMS already holds, empty for a BPMS which holds none of it
   */
  void reportIdentifiersTheBpmsAlreadyHolds(
      String adapterId,
      String workflowModuleId,
      Collection<IdentifierHeldElsewhere> found);

  /**
   * One identifier of a workflow module which the BPMS already holds, used by
   * {@link #reportIdentifiersTheBpmsAlreadyHolds}.
   *
   * @param kind Which of the scoped forms this identifier is
   * @param plainIdentifier The identifier as the application knows it, without any
   *          prefix - the core composes the scoped form
   * @param bpmnProcessId The BPMN process a task definition belongs to, since task
   *          definitions are scoped per process by default, and <code>null</code> for
   *          every other kind
   * @param heldBy How the BPMS names the holder, written by the ADAPTER because only it
   *          knows what its BPMS can say about one: a deployment name, a resource name, a
   *          version, a time
   * @param certainlyForeign Whether the adapter can prove that the holder is not an
   *          earlier deployment of this application. Where it cannot, the warning says so
   *          and the reader can weigh the line
   */
  record IdentifierHeldElsewhere(
                                 ScopedIdentifierKind kind,
                                 String plainIdentifier,
                                 String bpmnProcessId,
                                 String heldBy,
                                 boolean certainlyForeign) {
  }

  /**
   * The kinds of identifier a workflow module scopes, as far as a BPMS can be asked
   * about them.
   * <p>
   * Two of them can be answered today, and only by a BPMS with a repository to search:
   * Camunda 7 and Camunda 8 both answer for a BPMN process id and for a DMN decision id,
   * one query each. The Process-Engine-API answers for nothing, because its API has no
   * read method at all.
   * <p>
   * The other two are here although no BPMS serves them, so that an adapter for a BPMS
   * which does keep a registry of message names fills
   * {@link #MODULE_IDENTIFIER} without this SPI changing. Why nobody serves them is not
   * cost: a message name, a signal name, a BPMN error code and an escalation code live
   * only inside a BPMN model, and an engine which parses a model into its own structures
   * keeps no index of those names. A task definition is the fifth of that group and
   * misses for a second reason, namely that it only becomes visible while a job or an
   * external task for it exists, and on Camunda 7 it is not scoped in the first place.
   */
  enum ScopedIdentifierKind {

    /** A BPMN process id, scoped by the workflow module. */
    BPMN_PROCESS_ID,

    /**
     * An identifier scoped by the workflow module alone: a message name, a signal name,
     * a BPMN error code or an escalation code.
     */
    MODULE_IDENTIFIER,

    /** A task definition, scoped by the workflow module and by its BPMN process. */
    TASK_DEFINITION,

    /** A DMN decision id, scoped by the workflow module. */
    DMN_DECISION_ID

  }

}
