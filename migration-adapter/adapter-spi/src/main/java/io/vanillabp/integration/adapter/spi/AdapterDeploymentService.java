package io.vanillabp.integration.adapter.spi;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import io.vanillabp.integration.adapter.spi.health.AdapterHealth;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;

/**
 * An implementation is responsible for reading the BPMN and transforming it into the model type.
 * Additionally, the final model will be deployed to the target BPMS.
 * The implementation has to be provided by the platform integration adapter.
 * <p>
 * Conceptually, an adapter is &quot;the wiring service with deployment&quot;: it inherits
 * preparing/wiring and starting/stopping of workflow processing from
 * {@link ExtensionWiringService} and adds reading and deploying of BPMS resources.
 * <p>
 * <i>Hint:</i> The {@link PC} is an object holding all information needed by the adapter to deploy the resources.
 * <p>
 * <i>Note:</i> A DMN file travels as BYTES ({@link #readDmn}), not as a model type of its
 * own: no adapter has to understand a decision table, and the one thing which has to be
 * rewritten - the decision id, where a workflow module is scoped by prefixes - is the same
 * for every BPMS and is done by {@link DmnDecisionIds}.
 *
 * @param <BPMN> The BPMN model type
 * @param <PC> The context to store all information needed by the adapter for wiring and deploying BPMN
 */
public interface AdapterDeploymentService<BPMN, PC> extends ExtensionWiringService<BPMN, PC> {

  /**
   * @return The ID of the adapter implementing this interface
   */
  String getAdapterId();

  /**
   * @return The type of the adapter implementing this interface
   */
  String getAdapterType();

  /**
   * What this adapter can say about the BPMS it talks to right now, asked by the
   * platform integration whenever a health endpoint is called (Spring Boot Actuator's
   * health, Quarkus' readiness). The typical implementation is the cheapest question
   * the BPMS answers - Camunda 8 asks the cluster for its topology, which is what
   * Camunda's own starter does.
   * <p>
   * The default is <code>null</code>: an adapter which has nothing to check is
   * ABSENT from the endpoint rather than reported as healthy, and an adapter written
   * before this existed keeps working unchanged.
   * <p>
   * Two rules of the contract are worth repeating here, because they are easy to get
   * wrong (see {@link AdapterHealth}):
   * <ul>
   * <li>an adapter whose connection is not configured yet answers
   * {@link AdapterHealth.Status#UNKNOWN}, never {@link AdapterHealth.Status#DOWN} -
   * the application booted with a guiding warning on purpose;</li>
   * <li>the implementation must RETURN rather than throw, and it must come back
   * within a bounded time - the platform calls it on the thread serving the health
   * request. A failure to reach the BPMS is a {@link AdapterHealth.Status#DOWN} with
   * the reason in the description, not an exception (the core turns a thrown one into
   * {@link AdapterHealth.Status#DOWN} as a backstop).</li>
   * </ul>
   *
   * @return What the adapter found, or <code>null</code> to contribute nothing
   */
  default AdapterHealth checkHealth() {

    return null;

  }

  /**
   * Reads the given BPMN input stream and transforms it into the model type.
   *
   * @param workflowModuleId The workflow module ID
   * @param filename The filename of the BPMN file (used for logging and error messages)
   * @param bpmn The BPMN input stream. It is owned and closed by the deployment
   *        pipeline - implementations must NOT close it.
   * @param isVanillaBpBpmn Whether the input stream is VanillaBP or specific to the adapter's BPMS
   * @return The models of the executable processes found in the BPMN (key is the process ID, value is the model)
   * @throws BpmnParseException If the parsing fails
   */
  List<Map.Entry<String, BPMN>> readBpmn(
      String workflowModuleId,
      String filename,
      InputStream bpmn,
      boolean isVanillaBpBpmn) throws BpmnParseException;

  /**
   * Prepares the given model according to the feature of the adapter (e.g. setting defaults, etc.).
   *
   * @param workflowModuleId The workflow module ID
   * @param existingContext The existing context (usually from a previous BPMN) or null for the first BPMN
   * @param filename The filename of the BPMN file (used for logging and error messages)
   * @param bpmnProcessId The BPMN process ID
   * @param model The model
   * @return The context passed to startProcessing (usually used to collect wiring
   *         information); must NEVER be null - it is threaded through the whole
   *         deployment pipeline
   */
  PC prepareBpmn(
      String workflowModuleId,
      PC existingContext,
      String filename,
      String bpmnProcessId,
      BPMN model);

  /**
   * Takes one DMN file of the workflow module and adds it to the processing context, so
   * that {@link #deployResources(String, Object)} deploys it together with the module's
   * processes - a decision a business rule task calls is part of the module and travels
   * with it.
   * <p>
   * The DMN files of a module are read AFTER its BPMN files, so the context this is given
   * exists: a module without an executable process is skipped by the pipeline entirely.
   * <p>
   * Where the module is scoped by prefixes rather than by an isolation of the BPMS, the
   * decision ids of the file have to be rewritten before it is deployed, and the
   * reference from a business rule task rewritten with them - see {@link DmnDecisionIds}
   * for both halves and for why only the second one is the adapter's business.
   * <p>
   * The default takes no file and says so once per file: an adapter written before this
   * existed keeps working, and an application which brings a decision table to a BPMS
   * which cannot hold one is told rather than left with a business rule task nobody
   * deployed anything for.
   *
   * @param workflowModuleId The workflow module ID
   * @param existingContext The context the module's BPMN files produced; never null
   * @param filename The filename of the DMN file (used for logging and error messages)
   * @param dmn The DMN input stream. It is owned and closed by the deployment pipeline -
   *          implementations must NOT close it.
   * @return The context the DMN was added to; must never be null
   */
  default PC readDmn(
      final String workflowModuleId,
      final PC existingContext,
      final String filename,
      final InputStream dmn) {

    org.slf4j.LoggerFactory
        .getLogger(AdapterDeploymentService.class)
        .warn(
            """
                The adapter '{}' does not deploy DMN files, so the decision table '{}' of workflow \
                module '{}' was NOT deployed to its BPMS - a business rule task calling it will fail \
                at runtime. To solve this either deploy the decision to that BPMS by other means, or \
                remove the file from the module's resources location if the BPMS of this adapter is \
                not meant to serve those workflows.""",
            getAdapterId(),
            filename,
            workflowModuleId);
    return existingContext;

  }

  /**
   * What this BPMS knows about the versions of a BPMN process this application DECLARES
   * but deployed nothing under - the case a renamed BPMN process leaves behind. The old
   * id stays in the BPMS with every version ever deployed under it and with the workflows
   * still running on them, while the application brings only the new name and says so
   * with <code>&#64;WorkflowService(secondaryBpmnProcesses = ...)</code>.
   * <p>
   * The core asks once per such id after the workflow module finished deploying, because
   * only it knows which ids an application declared, and it registers what comes back
   * like a catalog handed over by
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#registerProcessVersions}
   * during <code>wireBpmn</code>. So the startup check for old process versions reaches
   * the versions of the old id as well, instead of learning about a method nobody kept as
   * an incident on a live workflow.
   * <p>
   * An adapter answering <code>null</code> - the default, and the honest answer of a BPMS
   * which cannot be asked about the versions of a process - keeps working unchanged: the
   * check stays silent about that id, exactly as before this method existed.
   * <p>
   * The id is the PLAIN one, so an adapter scopes it the way it scopes every other id (a
   * prefix, a tenant) before it asks its BPMS. Costs one query per declared-only id and
   * per boot, which is the shape decision 19 in the repository's DECISIONS.md asks for:
   * it follows the declarations of the application, not its history.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID nothing was deployed under
   * @return The versions of that process, or <code>null</code> where this BPMS cannot say
   */
  default io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog processVersionCatalogOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return null;

  }

  /**
   * Deploys the resources (process, decision matrix) to the target BPMS.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmsProcessingContext The processing context specific to the BPMS; never
   *        null - modules without any executable BPMN process are skipped by the
   *        deployment pipeline (with a warning) instead of being deployed
   * @throws IllegalStateException If the deployment fails
   */
  void deployResources(
      String workflowModuleId,
      PC bpmsProcessingContext) throws IllegalStateException;


  /**
   * Validates that several configured adapter ids of THIS adapter type address
   * DIFFERENT systems. Two instances of one BPMS type only make sense
   * if they do - and whether two configurations differ is BPMS knowledge, so the
   * decision belongs to the adapter:
   * <ul>
   * <li>an embedded engine: different databases/schemas (e.g. Camunda 7's
   * <code>data-source-name</code> or <code>table-prefix</code>),</li>
   * <li>a remote BPMS: different endpoints or different credentials addressing
   * the same endpoint (e.g. Camunda 8's addresses respectively the combination of
   * cluster and client id).</li>
   * </ul>
   * Called ONCE per adapter type at startup (before any deployment) on the first
   * deployment service of that type, with the ids in the order they are prioritized,
   * and only if more than one id of the type is configured - see
   * {@code DeploymentServiceTest$DistinctAdapterInstancesTests}, which is what holds
   * that. Ids which cannot be told apart must fail the boot with a guiding message
   * naming the property which makes them distinct.
   * <p>
   * The default does nothing - an adapter whose instances cannot be compared (the
   * connection is provided by the application) should say so in its documentation
   * rather than pretend.
   *
   * @param adapterIdsOfThisType The configured adapter ids of this adapter's type,
   *          in the order they are prioritized
   */
  default void validateDistinctAdapterInstances(
      final List<String> adapterIdsOfThisType) {

  }

  /**
   * The {@link NameClashAvoidance} mode which applies to this adapter as long as the
   * application configures none at any level. {@link NameClashAvoidance#BY_ADAPTER} -
   * version 1's behavior, which every adapter keeps, so an application upgrading
   * without touching its configuration keeps addressing the workflows it started
   * before (Camunda 7 and Camunda 8 both deployed a tenant named after the workflow
   * module in version 1). Overriding it is a last resort rather than a taste: an
   * application which never configured the mode would silently deploy under other
   * identifiers than before, and its running workflows would not be found.
   * <p>
   * What an adapter DOES do is say what its BPMS needs for the mode. The
   * Process-Engine-API has no isolation of its own and refuses
   * {@link NameClashAvoidance#BY_ADAPTER} while deploying; a Camunda 8 cluster
   * without multi-tenancy rejects a tenant id, and the adapter turns that into a boot
   * failure naming {@link NameClashAvoidance#USE_PREFIX} and
   * {@link NameClashAvoidance#NONE} as the ways out.
   * <p>
   * Whichever mode an adapter picks, the core keeps the choice visible:
   * {@link NameClashAvoidance#NONE} is reported at startup by
   * {@link #warnAboutUnscopedIdentifiers(String, boolean)}.
   *
   * @return The mode applying without configuration, never <code>null</code>
   */
  default NameClashAvoidance defaultNameClashAvoidance() {

    return NameClashAvoidance.BY_ADAPTER;

  }

  /**
   * Whether this adapter's own isolation mechanism would put the two given workflow
   * modules into DIFFERENT scopes of its BPMS. Asked by the core while it checks whether
   * two BPMN processes reach the BPMS under one identifier
   * ({@link NameClashAvoidanceSupport#validateNoCollidingProcessIds}) and the mode which
   * applies is {@link NameClashAvoidance#BY_ADAPTER}. That mode leaves the identifiers
   * plain, so the core holds two equal strings and still cannot say whether they collide:
   * what keeps the modules apart there is the BPMS, and the mechanism is yours.
   * <p>
   * Answer about the scope you would REALLY deploy those two modules to, never about the
   * configuration you happen to read. A tenant id is resolvable per adapter, per workflow
   * module and per workflow, so two modules can land in one tenant without a single line of
   * the application saying so. Resolve the scope for each of the two modules and compare
   * the results, which is what both Camunda adapters do with their tenants. A module which
   * would reach the BPMS under no scope at all has a scope like any other, and two such
   * modules are not separated.
   * <p>
   * The default answers <code>false</code>, which reads as "my isolation separates
   * nothing". That is the honest answer for a BPMS without isolation of its own, the
   * Process-Engine-API being one, and it is also the safe side of a question an adapter has
   * not answered yet: the check then refuses instead of letting a collision through.
   *
   * @param oneWorkflowModuleId One of the two workflow modules
   * @param anotherWorkflowModuleId The other one
   * @return Whether the BPMS itself would keep the two apart
   */
  default boolean ownIsolationSeparatesWorkflowModules(
      final String oneWorkflowModuleId,
      final String anotherWorkflowModuleId) {

    return false;

  }

  /**
   * Reports that a workflow module reaches this adapter's BPMS with
   * {@link NameClashAvoidance#NONE}, so nothing keeps its identifiers apart from
   * those of the other workflow modules. Called by the core once per workflow module
   * and adapter id as soon as the mode is resolved (i.e. while deploying, at
   * startup), no matter whether the mode was configured or is the adapter's
   * {@link #defaultNameClashAvoidance() default}.
   * <p>
   * The message belongs to the ADAPTER because the alternatives do: Camunda 8 can
   * prefix, use tenants (on a cluster with multi-tenancy enabled) or give a workflow
   * module a cluster of its own, whereas Camunda 7 can prefix, use tenants or run a
   * separate engine per module (<code>data-source-name</code>,
   * <code>table-prefix</code>). The default names what every BPMS can offer -
   * adapters override it to name their own options.
   *
   * @param workflowModuleId The workflow module whose identifiers are not scoped
   * @param fromDefault Whether the mode is this adapter's default (nothing is
   *          configured) rather than a configured value
   */
  default void warnAboutUnscopedIdentifiers(
      final String workflowModuleId,
      final boolean fromDefault) {

    org.slf4j.LoggerFactory
        .getLogger(getClass())
        .warn(
            """
                Workflow module '{}' is deployed to adapter '{}' with name-clash-avoidance 'none'{}, \
                so its BPMN process ids, message names and task definitions reach the BPMS as they \
                are. Nothing protects them: a second workflow module using the same identifier \
                addresses the same processes and tasks, and neither VanillaBP nor the BPMS can tell. \
                Keep 'none' only as long as your identifiers are unique across ALL workflow modules \
                of this application, otherwise choose:
                  vanillabp.adapters.{}.name-clash-avoidance: use-prefix   # VanillaBP prefixes the identifiers with the workflow module id
                  vanillabp.adapters.{}.name-clash-avoidance: by-adapter   # the BPMS' own isolation mechanism
                The same key may be set per workflow module \
                (vanillabp.workflow-modules.{}.adapters.{}.name-clash-avoidance). The mode is not a \
                runtime switch - changing it once workflows are running is a BPMS migration.""",
            workflowModuleId,
            getAdapterId(),
            fromDefault
                ? " (nothing is configured, so the adapter's default applies)"
                : "",
            getAdapterId(),
            getAdapterId(),
            workflowModuleId,
            getAdapterId());

  }

}
