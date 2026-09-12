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
   * Fails with a guiding message where two (workflow module, BPMN process ID) pairs of
   * this application reach the BPMS under the SAME identifier. That is the one clash which
   * loses a model: the BPMS keeps one definition under the identifier and not the other,
   * so one of the two workflow modules runs on a model nobody deployed. Called by the
   * adapter once it knows the deployed processes of the workflow module it is deploying
   * (the BPMN has to be read first).
   * <p>
   * The core REMEMBERS what the earlier calls of this boot passed, per adapter id, so the
   * SCOPE OF THE COLLECTION DOES NOT MATTER: hand over the processes of the workflow module
   * being deployed and a collision with an earlier module is found all the same. Two
   * adapter ids never see each other's processes, because two ids of one BPMS type are what
   * a migration looks like and each deploys its own scope. Passing the same pair twice is
   * not a collision either, which one BPMN file holding several processes needs.
   * <p>
   * What remembering costs is that the boot ends while the SECOND of the two modules
   * deploys, with the first one already in the BPMS. Why a half-deployed application is the
   * lesser evil here is decision 41 in the repository's DECISIONS.md.
   * <p>
   * Whether two equal identifiers are a collision at all depends on the mode. Under
   * {@link NameClashAvoidance#USE_PREFIX} the core composed both strings itself and needs
   * nobody else; under {@link NameClashAvoidance#NONE} nothing is scoped, so nothing
   * separates the two sides by definition; under {@link NameClashAvoidance#BY_ADAPTER} the
   * scoped form is the plain one and the BPMS is supposed to keep the modules apart, so the
   * core asks the adapter
   * ({@link AdapterDeploymentService#ownIsolationSeparatesWorkflowModules}) and refuses only
   * where the answer is that nothing separates them.
   *
   * @param adapterId The adapter ID
   * @param deployedProcesses The (workflow module ID, PLAIN BPMN process ID) pairs of the
   *          workflow module being deployed
   * @throws IllegalStateException Naming both workflow modules, both plain process ids, the
   *           identifier they share, the mode which produced it and the property key which
   *           sets that mode
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
   * The kinds of identifier a workflow module scopes, and which of them a BPMS can be
   * asked about.
   * <p>
   * Two of them are answered by a query: Camunda 7 and Camunda 8 both answer for a BPMN
   * process id and for a DMN decision id, one search each against their repository. The
   * Process-Engine-API answers for nothing, because its API has no read method at all.
   * <p>
   * The other kinds are not kept in any index, and that is not the same as not being
   * knowable. A message name, a signal name, a BPMN error code and an escalation code live
   * inside a BPMN model, and a BPMS keeps the models and hands them back: Camunda 7 through
   * <code>RepositoryService#getBpmnModelInstance</code>, Camunda 8 through
   * <code>newProcessDefinitionGetXmlRequest</code>, and both adapters read models that way
   * already. So those names CAN be determined, by reading a model rather than by asking an
   * index. What rules the complete answer out is the cost of reading one model per
   * definition version the BPMS holds, which grows for as long as the application is in
   * production, and that is what decision 19 in the repository's DECISIONS.md forbids a
   * start to do. Where the models are read anyway,
   * {@link io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog#identifiersOfVersion}
   * answers the same question for the versions of this application's own processes.
   * <p>
   * A task definition is read off a held model like the rest, and what differs is whether
   * it is scoped at all, which is a property of the BPMS. On Camunda 7 task definitions are
   * process-local: the expression is evaluated inside the process by VanillaBP's EL
   * resolver, nothing subscribes to them engine-wide, so that adapter deliberately leaves
   * them as they are and the question does not exist there. A Camunda 8 job type is the
   * opposite case and is prefixed, because a job type is what a worker subscribes to,
   * cluster-wide.
   */
  enum ScopedIdentifierKind {

    /** A BPMN process id, scoped by the workflow module. */
    BPMN_PROCESS_ID,

    /** A message name, scoped by the workflow module. */
    MESSAGE_NAME,

    /** A signal name, scoped by the workflow module. */
    SIGNAL_NAME,

    /** A BPMN error code, scoped by the workflow module. */
    ERROR_CODE,

    /** An escalation code, scoped by the workflow module. */
    ESCALATION_CODE,

    /** A task definition, scoped by the workflow module and by its BPMN process. */
    TASK_DEFINITION,

    /** A DMN decision id, scoped by the workflow module. */
    DMN_DECISION_ID

  }

  /**
   * Warns where two workflow modules of THIS application declare an identifier which ends
   * up as the same scoped form, so the BPMS cannot tell the two apart. Called by the
   * adapter once per workflow module while it deploys, with what it read out of the models
   * of that module: the adapter rewrites every message name, signal name, error code,
   * escalation code and task definition while it scopes that model anyway, so it holds all
   * of them and the question costs nothing extra. BPMN process ids have their own check,
   * {@link #validateNoCollidingProcessIds}.
   * <p>
   * A task definition is the most expensive of them to get wrong where the BPMS subscribes
   * to it cluster-wide, which a Camunda 8 job type is: two workflow modules using the same
   * task definition under {@link NameClashAvoidance#NONE} end up with one job type, and the
   * worker of one module fetches the jobs of the other. An adapter whose BPMS keeps task
   * definitions process-local reports none of them, and nothing here needs to know which of
   * the two its BPMS is.
   * <p>
   * Under {@link NameClashAvoidance#USE_PREFIX} the two scoped forms differ by the module
   * id, so there is nothing to report, and a task definition additionally differs by its
   * process unless <code>prefix-task-definitions-per-process</code> is switched off. Two
   * processes of one module sharing a task definition after that was switched off is the
   * application's own choice and stays silent, exactly like a shared message name inside one
   * module. The modes which let two modules share a name are
   * {@link NameClashAvoidance#NONE}, where nothing is scoped, and
   * {@link NameClashAvoidance#BY_ADAPTER} where one <code>tenant-id</code> is configured
   * for the whole adapter, so every workflow module of the application lands in the same
   * scope of the BPMS.
   * <p>
   * This warns rather than ending the boot, although both sides belong to this application
   * and the reasoning of decision 38 about a foreign deployment which runs correctly does
   * not apply here. Two reasons of its own do. Nothing is overwritten: unlike two BPMN
   * processes ending up under one id, where the BPMS keeps one definition and loses the
   * other, both modules keep their own models and only the runtime meaning of the name
   * becomes ambiguous, which an application may have arranged on purpose - one module
   * broadcasting a signal another one catches is a design, not a defect. And an application
   * which ran like this yesterday must not be stopped by an upgrade. What this must not do
   * is stay silent, because nothing else tells a developer that a message meant for one
   * module can reach a workflow of the other.
   * <p>
   * Several BPMN processes of ONE workflow module sharing a name is ordinary VanillaBP and
   * is never reported: the scope is the module.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module whose models were read
   * @param declared The identifiers those models declare, plain and without duplicates
   */
  void reportIdentifiersTheModelsDeclare(
      String adapterId,
      String workflowModuleId,
      Collection<ModelIdentifier> declared);

  /**
   * Warns where ONE version a BPMS still holds declares an identifier which the current
   * deployment of ANOTHER workflow module uses, which is the name clash a workflow module
   * deployed years ago leaves behind. Called by the CORE while it checks the versions a
   * BPMS still holds, so an adapter does not call this: what an adapter answers is
   * {@link io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog#identifiersOfVersion},
   * read from the model the BPMS hands back.
   * <p>
   * A task definition of a held version is worth as much as the rest where the BPMS
   * subscribes to it: a job type of a version workflows still run on is live, and the worker
   * of another workflow module fetching those jobs is the same defect as at deployment time.
   * <p>
   * Most of what arrives here is no finding. A held version of the same workflow module
   * declaring a name its current model declares as well is continuity. Several processes of
   * one module sharing a name is ordinary. The finding is a held version of one workflow
   * module carrying a name the current deployment of a different one scopes to the same
   * form.
   * <p>
   * Always a warning, never a refusal, and for a reason the deployment-time checks do not
   * have: nobody can change the held model any more, and workflows may still run on it and
   * run correctly. The count of those workflows is what the message says out loud, because
   * it decides how urgent the line is.
   * <p>
   * The limit of this check is which models get read at all. The core asks a catalog of a
   * workflow module the application still deploys; a module the application dropped
   * entirely has no catalog, nothing is asked about it, and its names stay invisible.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module of the held version
   * @param bpmnProcessId The plain BPMN process ID of the held version
   * @param version The version identifier the BPMS reported
   * @param activeWorkflows How many workflows still run on that version, or
   *          <code>null</code> where the BPMS cannot say
   * @param declared What that version's model declares, or <code>null</code> where the
   *          BPMS cannot read it
   */
  void reportIdentifiersOfHeldVersion(
      String adapterId,
      String workflowModuleId,
      String bpmnProcessId,
      String version,
      Long activeWorkflows,
      Collection<ModelIdentifier> declared);

  /**
   * One identifier a BPMN model declares, as the application knows it.
   *
   * @param kind Which of the scoped forms this identifier is
   * @param plainIdentifier The identifier without any prefix - the core composes the scoped
   *          form
   * @param bpmnProcessId The BPMN process which declares it, for a task definition, since
   *          those are scoped per process unless the application switched that off, and
   *          <code>null</code> for every kind the workflow module scopes alone
   */
  record ModelIdentifier(
                         ScopedIdentifierKind kind,
                         String plainIdentifier,
                         String bpmnProcessId) {
  }

}
