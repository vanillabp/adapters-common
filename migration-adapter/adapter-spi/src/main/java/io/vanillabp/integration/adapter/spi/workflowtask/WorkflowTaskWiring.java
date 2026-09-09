package io.vanillabp.integration.adapter.spi.workflowtask;

import java.util.Collection;

/**
 * What a BPMS adapter calls back into VanillaBP's core WHILE IT DEPLOYS, implemented by
 * the core (the migration adapter) and handed to adapters by the platform integration.
 * The runtime counterpart is {@link WorkflowTaskInvoker}, which the adapter's worker
 * threads hold - the two were one interface of thirty methods until it became clear that
 * a mandatory call an adapter can forget WILL be forgotten by the next adapter (Camunda 7
 * forgot {@link #validateNoUnwiredWorkflowTaskMethods(String)} for a year, and a typo in
 * a task definition stayed silent until a workflow reached the task).
 *
 * <h2>What is due when</h2>
 *
 * Per BPMN process, while <code>wireBpmn</code> runs - the adapter is the only one which
 * can read its own BPMN dialect, so everything the core needs about a model arrives here:
 * <ul>
 * <li>{@link #validateTaskWiring(String, String, Collection)} - every BPMN task has a
 * <code>&#64;WorkflowTask</code> method. Throwing from <code>wireBpmn</code> honors the
 * <code>deployment-failure</code> policy;</li>
 * <li>{@link #taskParameterNames(String, String, String)} - if your BPMS ships a variable
 * payload with a delivery, you have to know the names BEFORE you subscribe;</li>
 * <li>{@link #workflowTaskCompletesAsynchronously(String, String, String)} - refuse a
 * wiring which cannot keep a task open;</li>
 * <li>{@link #workflowTaskCompletesAsynchronously(String, String, String)} and
 * {@link #workflowsShareTheWorkflowAggregate(String, String, String)} - what the model
 * has to be rewritten for;</li>
 * <li>{@link #reportConcurrentTokenElements(String, String, Collection)} - the elements
 * which can put a second token into a workflow;</li>
 * <li>{@link #registerProcessVersions(String, String, String, ProcessVersionCatalog)} -
 * only where your BPMS can place version tags;</li>
 * <li>{@link #unsharedWorkflowAggregateProperties(String, String, Collection, io.vanillabp.integration.adapter.spi.AggregateSyncMode)} -
 * what a model reads but the aggregate does not share.</li>
 * </ul>
 * At the end of <code>deployResources</code>, per BPMN process:
 * {@link #registerDeployedVersion(String, String, String, String)} - also when your BPMS
 * deployed nothing because nothing changed. Only the adapter knows which version its BPMS
 * ended up with, which is why this one stays here.
 * <p>
 * What an adapter may ASK at any point of the pipeline, rather than having to report:
 * {@link #taskWiringOfProcessesNobodyDeployed(String)} - what the application's methods
 * serve for a BPMN process it declares without bringing a model, which is what a renamed
 * BPMN process leaves behind.
 * <p>
 * <b>What the core does on its own</b>, once the last adapter of a workflow module
 * finished deploying: {@link #validateNoUnwiredWorkflowTaskMethods(String)},
 * {@link #registerVersionsOfProcessesNobodyDeployed(String, String, java.util.function.BiFunction)},
 * {@link #resolveProcessVersions(String)} and the report about the processes
 * {@link #bpmnProcessesWithoutWorkflowService(String)} names. All four are module-level
 * and answered from what the application declared next to what the adapters wired, so the
 * core knows the moment and takes the duty - an adapter must NOT call them.
 */
public interface WorkflowTaskWiring {

  /**
   * Validates that every given BPMN task is served by a
   * <code>&#64;WorkflowTask</code> method of the process' workflow service(s). All
   * unmatched tasks are collected and reported in ONE exception with guiding
   * messages. Additionally every matched method is marked as wired - the input for
   * {@link #validateNoUnwiredWorkflowTaskMethods(String)}.
   * <p>
   * A process NO workflow service claims is not validated at all and does not end the
   * boot: a BPMN file travels to the BPMS as a whole, so a process drawn next to the
   * one the application asked for is deployed with it, and demanding methods for a
   * model somebody else owns would stop the application over a file it cannot change.
   * Such a process is reported by the deployment instead, see
   * {@link #bpmnProcessesWithoutWorkflowService(String)}. Call this method for every
   * executable process of a file anyway - it is what makes the report complete.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param tasks The tasks of the executable BPMN process to be wired
   * @throws IllegalStateException If a BPMN task of a CLAIMED process has no matching
   *           method
   */
  void validateTaskWiring(
      String workflowModuleId,
      String bpmnProcessId,
      Collection<BpmnTaskSpec> tasks);

  /**
   * The BPMN processes of the workflow module which
   * {@link #validateTaskWiring(String, String, Collection)} was called for while no
   * <code>&#64;WorkflowService</code> class of the application claims them, sorted by
   * process id and free of duplicates however many adapters wired them.
   * <p>
   * The core asks this once a workflow module finished deploying, to say in ONE
   * message what such a process costs. An adapter neither implements nor calls it -
   * the default keeps a test double of this SPI compiling.
   *
   * @param workflowModuleId The workflow module ID
   * @return The unclaimed BPMN process IDs; empty where every wired process is served
   */
  default Collection<String> bpmnProcessesWithoutWorkflowService(
      final String workflowModuleId) {

    return java.util.List.of();

  }

  /**
   * Reports the elements of a BPMN process which can put a SECOND token into a
   * running workflow - a non-interrupting boundary event, a parallel or inclusive
   * gateway forking into several flows, a parallel multi-instance activity, a
   * non-interrupting event subprocess, an ad-hoc subprocess. Called during
   * <code>wireBpmn</code>, since only the adapter can read its BPMN dialect.
   * <p>
   * The ad-hoc subprocess belongs on that list whatever the model around it says. Which
   * of its activities run is decided while the workflow already stands there, so a model
   * activating one activity today activates two as soon as the data behind that choice
   * changes. A warning which appears only after such a change is worse than one which
   * appears always.
   * <p>
   * What it means is the core's decision: concurrent tokens mean two
   * branches writing the same workflow aggregate, and an aggregate without a version
   * attribute loses the writes of whichever branch commits first, without any error.
   * The core knows the aggregate class, so it warns once per BPMN process - naming
   * the elements reported here, which is why this method takes IDs rather than a
   * boolean.
   * <p>
   * An adapter whose BPMS cannot be asked about its models reports nothing; the check
   * stays silent then instead of guessing.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param elementIds The IDs of the elements producing a second token
   */
  default void reportConcurrentTokenElements(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<String> elementIds) {

  }

  /**
   * Validates - after ALL BPMN processes of a workflow module were wired - that
   * every <code>&#64;WorkflowTask</code> method matched a task of at least ONE of
   * the module's BPMN processes (a workflow service class may declare several
   * processes via {@code secondaryBpmnProcesses}, so a method unmatched in one
   * process may legitimately serve another - this check closes the second
   * direction the per-process {@link #validateTaskWiring} cannot decide). Called
   * by the adapter at the END of <code>deployResources</code>; throwing there
   * honors the <code>deployment-failure</code> policy.
   *
   * @param workflowModuleId The workflow module ID
   * @throws IllegalStateException Naming every method matching no task of any
   *           wired BPMN process, with the fix
   */
  void validateNoUnwiredWorkflowTaskMethods(
      String workflowModuleId);

  /**
   * Which of the given names are attributes of the workflow aggregate that are NOT
   * shared with the BPMS - the question behind the startup check for such expressions.
   * <p>
   * An embedded engine can read the BPMN model, and only the core knows what an
   * aggregate shares. So the adapter collects the identifiers its models read (a
   * condition, a timer, a multi-instance collection) and asks here which of them would
   * always be <code>null</code> in the engine although the application clearly meant an
   * attribute of its aggregate. A name which is no attribute at all is none of this
   * check's business: it may well be a variable the model itself provides.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param names The identifiers read by the model
   * @param adapterDefault What this adapter shares unless the application says
   *          otherwise
   * @return The names which are attributes of the aggregate but not shared, in the
   *         order given; empty if the BPMN process is unknown
   * @see #unsharedWorkflowAggregatePaths(String, String, java.util.Collection,
   *      io.vanillabp.integration.adapter.spi.AggregateSyncMode) for an adapter which can
   *      read a whole path out of its model, which is the fuller question
   */
  default java.util.Collection<String> unsharedWorkflowAggregateProperties(
      final String workflowModuleId,
      final String bpmnProcessId,
      final java.util.Collection<String> names,
      final io.vanillabp.integration.adapter.spi.AggregateSyncMode adapterDefault) {

    return java.util.List.of();

  }

  /**
   * The same question for a PATH an expression reads
   * (<code>order.customer.address.city</code>): which of the given paths stop short of a
   * value the BPMS holds, and where.
   * <p>
   * An adapter which can read the whole path out of its model asks this instead of
   * {@link #unsharedWorkflowAggregateProperties}, which answers about the first segment
   * only. The nested case is the one worth asking about: the shared values are a
   * structure, so an expression navigating into them meets the sync model at every
   * segment, and an unshared segment two levels down reads <code>null</code> just as a
   * top-level one does.
   * <p>
   * A path whose FIRST segment is no attribute of the aggregate at all is NOT reported,
   * for the same reason the single-name question leaves it alone: the model may well
   * provide a variable of that name. Everything below the first segment is reported,
   * because there the aggregate is what the expression navigates.
   * <p>
   * The core answers from the DECLARED types of the segments and stays silent wherever
   * they cannot decide, so a path which is missing from the answer means either "this
   * works" or "this cannot be judged" - never "this was checked and found broken".
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param paths The paths read by the model, segments separated by dots
   * @param adapterDefault What this adapter shares unless the application says
   *          otherwise
   * @return The reportable paths with what the walk found, keyed by the path as it was
   *         given; empty if the BPMN process is unknown
   */
  default java.util.Map<String, io.vanillabp.integration.adapter.spi.WorkflowAggregateSync.PathVerdict> unsharedWorkflowAggregatePaths(
      final String workflowModuleId,
      final String bpmnProcessId,
      final java.util.Collection<String> paths,
      final io.vanillabp.integration.adapter.spi.AggregateSyncMode adapterDefault) {

    return java.util.Map.of();

  }

  /**
   * The process variables the <code>&#64;WorkflowTask</code> method(s) serving the
   * given task definition (or BPMN activity ID) read with
   * <code>&#64;TaskParam</code> - the names as the annotation spells them.
   * <p>
   * A <code>&#64;TaskParam</code> is how the application reads what the BPMS
   * GENERATED on this path: a value an input or output mapping produced, the result
   * of a script or a decision, something the model computed rather than the
   * workflow aggregate holds. A BPMS which hands its worker a variable payload has
   * to know these names to keep that payload down to what is actually read, and the
   * core is the only place they exist - the adapter sees a
   * {@link TaskInvocationContext#getTaskParameter(String)} call one name at a time,
   * and only once the delivery is already there.
   * <p>
   * Several methods may serve one element (different process versions), so
   * the answer is the UNION of their parameters: the delivery has to satisfy
   * whichever of them runs. The names are sorted and duplicate-free, which is what a
   * subscription comparing itself across restarts needs (a Camunda 8 job stream is
   * equivalent to another one only if the fetched variables match).
   * <p>
   * The default answers nothing, which switches the derivation off rather than
   * making an adapter fetch an incomplete list.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskDefinitionOrActivityId The task definition or BPMN activity ID
   * @return The declared parameter names, sorted; empty if no method is registered
   *         or none of them declares a <code>&#64;TaskParam</code>
   */
  default Collection<String> taskParameterNames(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinitionOrActivityId) {

    return java.util.List.of();

  }

  /**
   * Whether two BPMN processes of one workflow module work on the SAME workflow
   * aggregate - which is what the declaration says: one class declares the process
   * to be started as its {@code bpmnProcess} and the others as
   * {@code secondaryBpmnProcesses}.
   * <p>
   * Adapters ask this about a call activity: a process called on the same aggregate
   * continues the same business case and has to reach the same aggregate, whereas a
   * process with an aggregate of its own must not be handed the caller's identity.
   * Camunda 7 needs the answer because it does not pass its business key - which
   * carries the aggregate's ID - to a called process on its own.
   * <p>
   * The default is <code>false</code>: the core answers this, and a test double of
   * this SPI should not invent an answer which makes an adapter change a model.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param otherBpmnProcessId The BPMN process ID to compare with
   * @return Whether both processes serve the same workflow aggregate;
   *         <code>false</code> if either of them is unknown
   */
  default boolean workflowsShareTheWorkflowAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String otherBpmnProcessId) {

    return false;

  }

  /**
   * Whether the <code>&#64;WorkflowTask</code> method serving the given task
   * definition (or BPMN activity ID) completes its task ASYNCHRONOUSLY, which a
   * method says by declaring a <code>&#64;TaskId</code> parameter: the task stays
   * open until the application completes it.
   * <p>
   * Adapters ask this while wiring, because a BPMN element which cannot stay open
   * is a modelling defect the developer should learn about while the application
   * starts rather than as an incident on a live workflow. Camunda 7's
   * <code>camunda:expression</code> is such an element - it completes the task as
   * soon as the expression returns.
   * <p>
   * ONE such method is enough for the answer to be <code>true</code>: several
   * methods may serve one element (different process versions), and an
   * element which cannot stay open is wired wrongly as soon as any of them wants to
   * keep it open.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskDefinitionOrActivityId The task definition or BPMN activity ID
   * @return Whether a matching method completes its task asynchronously;
   *         <code>false</code> if no method is registered at all
   */
  default boolean workflowTaskCompletesAsynchronously(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinitionOrActivityId) {

    // the core answers this; the default keeps test doubles of this SPI compiling
    // and switches such a check off rather than inventing an answer
    return false;

  }

  /**
   * The name of the workflow aggregate's ID property for the given BPMN process -
   * used by remote BPMS without a business-key concept: they store the aggregate's
   * ID as a process variable named after the ID property (the start commands write
   * it, the task workers read it back).
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return The ID property's name
   * @throws IllegalStateException If the BPMN process is not served by any
   *           workflow service (guiding message)
   */
  String resolveWorkflowAggregateIdName(
      String workflowModuleId,
      String bpmnProcessId);

  /**
   * Hands over what the BPMS knows about the deployed versions of a BPMN process,
   * called during <code>wireBpmn</code> by an adapter whose BPMS can tell. It serves
   * the <code>version</code> attribute of ALL annotations carrying one
   * (<code>&#64;WorkflowTask</code>, <code>&#64;WorkflowStartedByBpms</code>,
   * <code>&#64;WorkflowEnded</code>) and is needed only for specifications naming a
   * version TAG - specifications made of numbers are compared to the version the
   * adapter reports in its invocation contexts, without asking anybody.
   *
   * @param adapterId The adapter ID (the catalog answers for THIS BPMS)
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param catalog The versions of that process
   */
  default void registerProcessVersions(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog catalog) {

  }

  /**
   * Resolves the version tags the annotations of the given workflow module name, using
   * the catalogs registered by {@link #registerProcessVersions}. Called by the CORE
   * once per workflow module, after the module finished deploying and after the
   * catalogs of the ids nothing was deployed under arrived
   * ({@link #registerVersionsOfProcessesNobodyDeployed}) - so the version deployed by
   * this very boot AND every version tag of a renamed process' old id are part of the
   * answer, and version specifications naming a tag are ambiguous or unknown at
   * STARTUP instead of at the first task delivery. An adapter must NOT call this: an
   * adapter calling it at the end of its own <code>deployResources</code> resolves the
   * tags of the declared-only ids against nothing, because those catalogs arrive
   * later.
   *
   * @param workflowModuleId The workflow module ID
   * @throws IllegalStateException If two methods turn out to serve the same BPMN
   *           element in overlapping version ranges (guiding message)
   */
  default void resolveProcessVersions(
      final String workflowModuleId) {

  }

  /**
   * Registers what one BPMS holds for the BPMN processes of a workflow module which the
   * application DECLARES but deployed nothing under - what a renamed BPMN process leaves
   * behind, where the old id lives on in the BPMS with the workflows still running on it.
   * <p>
   * Called by the core once per adapter of a workflow module, right after the module
   * finished deploying: only the core knows which ids an application declared, and only
   * the adapter can ask its BPMS about them, which is what
   * {@link io.vanillabp.integration.adapter.spi.AdapterDeploymentService#processVersionCatalogOf}
   * is handed in here for. An adapter must NOT call this.
   * <p>
   * Which ids those are is the core's decision, and it asks about fewer than it could: an
   * id is asked about where the workflow service declaring it ALSO serves a BPMN process
   * this boot deployed. A workflow service whose processes were none of them deployed is
   * waiting for a model which has not arrived yet, which says nothing about a rename.
   *
   * @param workflowModuleId The workflow module ID
   * @param adapterId The adapter ID (the catalogs answer for THIS BPMS)
   * @param catalogOfProcess Answers what that BPMS holds for one (workflow module, plain
   *          BPMN process ID), or <code>null</code> where it cannot say
   */
  default void registerVersionsOfProcessesNobodyDeployed(
      final String workflowModuleId,
      final String adapterId,
      final java.util.function.BiFunction<String, String, io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog> catalogOfProcess) {

  }

  /**
   * What the <code>&#64;WorkflowTask</code> methods of a workflow module serve for the BPMN
   * processes it DECLARES without bringing a model for them, in the form an adapter composes
   * its BPMS' own identifiers from. An id nothing was deployed under is what renaming a BPMN
   * process leaves behind: the old name lives on in the BPMS with the workflows still
   * running on it, while the resources of the application carry the new one only.
   * <p>
   * An adapter asks this so that those workflows keep being served. The BPMS holds their
   * models, so it knows the tasks it will hand out for them, but the identifier it hands them
   * out under may carry the process id ({@link
   * io.vanillabp.integration.adapter.spi.NameClashAvoidance#USE_PREFIX} scopes a Camunda 8
   * job type by the process it was deployed with) - and then the subscriptions of the
   * deployed processes reach none of them. Whether that matters, and what to do about it, is
   * the adapter's to decide: compose a subscription per entry of this answer, read the models
   * the BPMS still holds under the id, or do nothing at all where such a workflow is served
   * anyway.
   * <p>
   * Which ids are named is the same question
   * {@link #registerVersionsOfProcessesNobodyDeployed} answers, and the same answer: an id
   * is named where the workflow service declaring it ALSO serves a BPMN process this boot
   * deployed. A workflow service whose processes were none of them deployed is waiting for
   * a model which has not arrived yet, which says nothing about a rename.
   * <p>
   * <b>What an entry holds</b> is what the application named its wiring by, plain and
   * unscoped, so an adapter scopes it the way it scopes the wiring of a model it deployed.
   * Today that is the <code>taskDefinition</code> of a method
   * (<code>&#64;WorkflowTask(taskDefinition = ...)</code>, which defaults to the method's
   * name), and a method naming a BPMN element id instead
   * (<code>&#64;WorkflowTask(id = ...)</code>) contributes nothing: an element is matched
   * through the model, and the model of that id is the one thing the application does not
   * have. So an id can be named with an EMPTY collection, and an adapter which composes its
   * identifiers from task definitions can say that those workflows are the ones it will not
   * reach.
   * <p>
   * Read an entry as "what to compose from" rather than as an identifier of your BPMS. What
   * an application may name its wiring by belongs to the surface of
   * <code>spi-for-java</code> and can widen, while what an adapter needs from the core does
   * not change, which is why this method is named after the question rather than after
   * today's answer - see decision 34 in the repository's DECISIONS.md. An adapter must
   * therefore not assume that a value is spelled the way its own BPMS spells one.
   * <p>
   * Answered after the workflow module finished deploying, which is when the difference
   * between declared and deployed is settled. Asking earlier answers less, never
   * something wrong. The default answers nothing, which switches the whole thing off
   * rather than making a test double of this SPI invent declarations.
   *
   * @param workflowModuleId The workflow module ID
   * @return What the methods serve, per plain BPMN process ID, both sorted; empty where the
   *         module declares no id it deployed nothing under
   */
  default java.util.Map<String, Collection<String>> taskWiringOfProcessesNobodyDeployed(
      final String workflowModuleId) {

    return java.util.Map.of();

  }

  /**
   * The version the BPMS assigned to the model THIS boot deployed, reported by the
   * adapter right after its deployment. It tells the core two things it
   * cannot know otherwise: which versions of that process are OLDER, so the startup
   * check knows what to look at, and which version must never be covered by
   * <code>outfaded-versions</code> - fading out the version the application just
   * deployed is a configuration error, and the boot says so.
   * <p>
   * An adapter whose BPMS counts no versions reports nothing, which switches both off.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param version The version identifier the BPMS assigned, or <code>null</code>
   */
  default void registerDeployedVersion(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

  }
}
