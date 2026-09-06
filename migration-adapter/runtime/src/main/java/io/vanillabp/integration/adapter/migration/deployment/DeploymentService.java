package io.vanillabp.integration.adapter.migration.deployment;

import java.io.InputStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import io.vanillabp.integration.adapter.migration.config.DeploymentFailurePolicy;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * A service responsible for deploying BPMS resources like BPMN and DMN files.
 */
@Slf4j
public class DeploymentService {

  /**
   * What a BPMN file is called - the files which bring the executable processes of a
   * workflow module.
   */
  public static final String BPMN_EXTENSION = ".bpmn";

  /**
   * What a DMN file is called - the decision tables a business rule task of the module
   * calls. They are read AFTER the BPMN files, because the processing context they are
   * added to is what reading a process produced.
   */
  public static final String DMN_EXTENSION = ".dmn";

  /**
   * @see DeploymentService#bpmsProcessingContexts
   * @param <PC> The processing context, used to store all information needed by the adapter to deploy the process.
   */
  @Builder
  @Getter
  private static class ToBeStarted<PC> {
    private AdapterDeploymentService<?, PC> deploymentService;
    private PC bpmsProcessingContext;
  }

  @Getter
  @Setter
  private static class BpmsProcessingContextHolder<PC> {
    private PC bpmsProcessingContext;
  }

  /**
   * VanillaBP properties.
   */
  private final MigrationAdapterProperties properties;

  /**
   * Deployment services of all adapters.
   */
  private final List<AdapterDeploymentService<?, ?>> deploymentServices;

  /**
   * Wiring services of extensions only. Since {@link AdapterDeploymentService} extends
   * {@link ExtensionWiringService}, adapters may show up in the list of wiring services
   * passed to the constructor (e.g. collected by type in a bean container) - they are
   * filtered out because adapters are wired explicitly as part of the deployment
   * pipeline.
   */
  private final List<ExtensionWiringService<?, ?>> wiringServices;

  /**
   * A map of workflow module ids and a list of process-contexts to be started after deployment and booting the application.
   * There is one entry per adapter the workflow module was deployed to (the union of the module-level and all
   * workflow-level prioritized adapters), since for BPMS migration all deployed adapters have to keep processing
   * workflows.
   */
  private final Map<String, List<ToBeStarted<?>>> bpmsProcessingContexts;

  /**
   * @param properties Attributes for configuration of the deployment process.
   * @param deploymentServices All adapters deployment services.
   */
  /**
   * The core's own wiring interface, used for what belongs to a whole workflow module and
   * no adapter has to remember (see {@link #runModuleLevelChecks(String)}). May be
   * <code>null</code> in tests which only exercise the pipeline itself.
   */
  private final io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring workflowTaskWiring;

  public DeploymentService(
      final MigrationAdapterProperties properties,
      final List<AdapterDeploymentService<?, ?>> deploymentServices,
      final List<ExtensionWiringService<?, ?>> wiringServices) {

    this(properties, deploymentServices, wiringServices, null);

  }

  public DeploymentService(
      final MigrationAdapterProperties properties,
      final List<AdapterDeploymentService<?, ?>> deploymentServices,
      final List<ExtensionWiringService<?, ?>> wiringServices,
      final io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring workflowTaskWiring) {

    this.workflowTaskWiring = workflowTaskWiring;
    this.properties = properties;
    this.deploymentServices = deploymentServices;
    this.wiringServices = new LinkedList<>(wiringServices
        .stream()
        // adapters are wired explicitly by the deployment pipeline (see field comment)
        .filter(wiringService -> !(wiringService instanceof AdapterDeploymentService))
        .toList());
    this.wiringServices.sort(Comparator.comparingInt(ExtensionWiringService::getOrder));
    bpmsProcessingContexts = new HashMap<>();

  }

  /**
   * Deploy all resources of all workflow modules to the BPMS. This is done
   * <ol>
   *   <li>for each adapter the workflow module has to be deployed to (union of the module-level
   *       and all workflow-level prioritized adapters)</li>
   *   <li>reading the BPMN/DMN files</li>
   *   <li>prepare the BPMN by the adapter (setting default behavior etc.)</li>
   *   <li>for each adapter and extension having the same model type and process context type (e.g. all for Camunda 8)</li>
   *   <ol>
   *     <li>wire the business code to the BPMN's tasks</li>
   *   </ol>
   *   <li>deploy the result to the BPMS</li>
   * </ol>
   * <p>
   * The adapters a workflow module is deployed to are the UNION of the module's
   * effective prioritized-adapters list and every adapter named in a workflow-level
   * <code>prioritized-adapters</code> override of that module
   * ({@link MigrationAdapterProperties#getDeploymentAdaptersFor(String)}): BPMS
   * election is process-granular while deployment is file-granular, so an adapter
   * prioritized for a single workflow only still has to receive the module's
   * resources - otherwise starting that workflow would fail at runtime. Every
   * adapter of the union receives the module's FULL resources (per-process
   * filtering was considered and rejected: BPMN files may contain several processes
   * and adapters deploy whole files - extra processes deployed to an adapter are
   * inert because workflow starts are routed by the election, and during a BPMS
   * migration having the module's complete model in both BPMS is even desirable).
   * <p>
   * A failing deployment aborts booting of the application unless the property
   * <code>vanillabp.adapters.&lt;id&gt;.deployment-failure</code> is set to
   * <code>warn</code> for the failing adapter <i>and</i> the adapter is not the
   * first-priority adapter of the workflow module or of any of the module's
   * workflows: in this case the failure is logged and the application still starts
   * (e.g. the old BPMS during a migration being temporarily unreachable). A failure
   * of an adapter being first priority for the module or for a single workflow
   * always fails the boot - new workflows could not be started otherwise.
   * <p>
   * After all adapters were processed, configured workflow IDs
   * (<code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;bpmnProcessId&gt;</code>)
   * which match no executable BPMN process found in the module's resources are
   * reported by a WARN (not a failure - the BPMN may arrive later, e.g. during a
   * migration) naming the known BPMN process IDs, and so are the executable BPMN
   * processes no <code>&#64;WorkflowService</code> class claims (see
   * {@link #reportBpmnProcessesWithoutWorkflowService(List, Map)}).
   *
   * @param workflowModuleIds The workflow module IDs to deploy
   * @param resourcesLoader Loads the resources of one location: it is given the location
   *     and a file extension ({@value #BPMN_EXTENSION} respectively
   *     {@value #DMN_EXTENSION}) and provides a map having the filename as the key and
   *     the file's input stream as the value.
   * @param <PC> The processing context, used to store all information needed by the adapter to deploy the process.
   */
  @SuppressWarnings("unchecked")
  public <PC> void deployResources(
      final List<String> workflowModuleIds,
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader) {

    // several ids of one adapter type only make sense if they address DIFFERENT
    // systems - which the ADAPTER decides
    validateDistinctAdapterInstances();

    // which file each executable BPMN process came from, per workflow module - what
    // the reports after the deployment name so a developer can open the right file
    final var bpmnFilesByProcessId = new HashMap<String, Map<String, String>>();

    // walk through all workflow modules
    workflowModuleIds
        .stream()
        // for each adapter the module has to be deployed to (union of the
        // module-level and all workflow-level prioritized adapters)...
        .flatMap(workflowModuleId -> properties
            .getDeploymentAdaptersFor(workflowModuleId)
            .stream()
            // ...find the right deployment service...
            .map(adapterId -> deploymentServices
                .stream()
                .filter(service -> service.getAdapterId().equals(adapterId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "No deployment service found for adapter '%s'!".formatted(adapterId)
                )))
            .map(deploymentService -> Map.entry(
                workflowModuleId,
                (AdapterDeploymentService<?, PC>) deploymentService)))
        // and process the resources of the workflow module specific to the adapter
        .forEach(deploymentServiceEntry -> {
          final var workflowModuleId = deploymentServiceEntry.getKey();
          final var deploymentService = deploymentServiceEntry.getValue();
          try {
            deployResourcesOfAdapter(
                workflowModuleId,
                deploymentService,
                resourcesLoader,
                bpmnFilesByProcessId.computeIfAbsent(workflowModuleId, id -> new LinkedHashMap<>()));
          } catch (final RuntimeException e) {
            final var adapterId = deploymentService.getAdapterId();
            final var policy = properties.getDeploymentFailureFor(adapterId);
            if ((policy == DeploymentFailurePolicy.WARN) && !properties.isFirstPriorityFor(workflowModuleId,
                adapterId)) {
              log.warn(
                  "Deployment of workflow module '{}' failed for adapter '{}'! Since "
                      + "'{}.adapters.{}.deployment-failure' is set to 'warn' and the adapter is not "
                      + "the first-priority adapter of the workflow module or of any of its workflows, "
                      + "the application starts anyway. Workflows of this "
                      + "workflow module are not processed by adapter '{}'!",
                  workflowModuleId,
                  adapterId,
                  MigrationAdapterProperties.PREFIX,
                  adapterId,
                  adapterId,
                  e);
            } else {
              throw e;
            }
          }
        });

    reportWorkflowModulesWithoutResources(workflowModuleIds);

    warnAboutConfiguredWorkflowsUnknownToBpmnResources(workflowModuleIds, bpmnFilesByProcessId);

    reportBpmnProcessesWithoutWorkflowService(workflowModuleIds, bpmnFilesByProcessId);

    workflowModuleIds.forEach(this::runModuleLevelChecks);

  }

  /**
   * What belongs to a workflow module as a whole, run once every adapter of the module
   * deployed: the versions the BPMS still holds for a BPMN process the application only
   * DECLARES, and the two checks the module is judged by.
   * <p>
   * They used to be the adapter's duty, written in the javadoc of the SPI and nowhere
   * else, and Camunda 7 forgot one of them for a year: a typo in a
   * <code>&#64;WorkflowTask(taskDefinition = ...)</code> stayed silent until a workflow
   * reached the task. Neither check needs anything an adapter knows - the module is
   * deployed, and that is the moment - so the core takes the duty instead of asking
   * every adapter author to remember it.
   * <p>
   * What stays with the adapter is
   * {@code WorkflowTaskWiring#registerDeployedVersion}: only the adapter knows which
   * version its BPMS ended up with.
   *
   * @param workflowModuleId The workflow module which finished deploying
   */
  private void runModuleLevelChecks(
      final String workflowModuleId) {

    if (workflowTaskWiring == null) {
      return;
    }
    // the versions of a declared-only id come first: the reverse wiring check needs
    // them to tell a method kept for a renamed process from one wired to nothing
    askAdaptersAboutProcessesNobodyDeployed(workflowModuleId);
    // then the reverse wiring check, because a method serving no task at all is the
    // more basic defect than two methods serving the same one in overlapping version
    // ranges, which is what the version resolution ends the boot over once the tags
    // are placed
    workflowTaskWiring.validateNoUnwiredWorkflowTaskMethods(workflowModuleId);
    workflowTaskWiring.resolveProcessVersions(workflowModuleId);

  }

  /**
   * Asks every adapter of the workflow module what its BPMS holds for the BPMN processes
   * the application DECLARED without deploying anything under them - the id a renamed
   * BPMN process left behind, which the BPMS still holds with the workflows running on
   * it.
   * <p>
   * The two ends of this could not meet before: an adapter registers the versions of the
   * processes it just deployed and cannot know which further ids an application declared,
   * while the core knows the declarations and cannot ask a BPMS. So the core asks, here,
   * where the module is deployed and every id it brought is known.
   *
   * @param workflowModuleId The workflow module which finished deploying
   */
  private void askAdaptersAboutProcessesNobodyDeployed(
      final String workflowModuleId) {

    properties
        .getDeploymentAdaptersFor(workflowModuleId)
        .forEach(adapterId -> deploymentServices
            .stream()
            .filter(deploymentService -> deploymentService.getAdapterId().equals(adapterId))
            .findFirst()
            .ifPresent(deploymentService -> workflowTaskWiring
                .registerVersionsOfProcessesNobodyDeployed(
                    workflowModuleId,
                    adapterId,
                    deploymentService::processVersionCatalogOf)));

  }

  /**
   * Reports a workflow module NO adapter found resources for. Each adapter already
   * warned about its own location, but a module none of them could serve is a
   * different message: nothing of this module runs, and the failure a developer meets
   * later comes from the BPMS ("no processes deployed with key ...") and names neither
   * VanillaBP nor a location.
   * <p>
   * The boot continues on purpose: BPMN may arrive with an adapter deployed later
   * (the migration case this platform exists for), and a module without resources
   * breaks nothing by itself.
   *
   * @param workflowModuleIds The workflow module IDs which were deployed
   */
  private void reportWorkflowModulesWithoutResources(
      final List<String> workflowModuleIds) {

    workflowModuleIds
        .stream()
        .filter(workflowModuleId -> !bpmsProcessingContexts.containsKey(workflowModuleId))
        .forEach(workflowModuleId -> log.error(
            "No BPMN resources were found for workflow module '{}' - by NONE of its adapters ({}). "
                + "No workflow of this module can be started; the BPMS would report an unknown "
                + "process. Locations searched: {}. Check where the module's BPMN files are packaged, "
                + "or name the location explicitly in "
                + "'{}.workflow-modules.{}.adapters.<adapter>.resources-location'.",
            workflowModuleId,
            String.join(", ", properties.getDeploymentAdaptersFor(workflowModuleId)),
            properties
                .getDeploymentAdaptersFor(workflowModuleId)
                .stream()
                .flatMap(adapterId -> properties
                    .getAdapterResourcesLocationsFor(workflowModuleId, adapterId)
                    .stream())
                .map(location -> "'%s'".formatted(location.location()))
                .distinct()
                .collect(java.util.stream.Collectors.joining(", ")),
            MigrationAdapterProperties.PREFIX,
            workflowModuleId));

  }

  /**
   * Asks each adapter type whose ids are configured more than once whether those
   * ids actually address DIFFERENT systems
   * ({@link AdapterDeploymentService#validateDistinctAdapterInstances(List)}).
   * Two instances of one BPMS sharing the same database/endpoint/credentials are
   * the same instance - configuring them as separate adapters is a defect the
   * adapter reports with a guiding message.
   */
  private void validateDistinctAdapterInstances() {

    final var deploymentServicesByType = new LinkedHashMap<String, List<AdapterDeploymentService<?, ?>>>();
    deploymentServices
        .forEach(deploymentService -> deploymentServicesByType
            .computeIfAbsent(deploymentService.getAdapterType(), type -> new LinkedList<>())
            .add(deploymentService));

    deploymentServicesByType.forEach((
        adapterType,
        servicesOfType) -> {
      if (servicesOfType.size() < 2) {
        return;
      }
      // in the order they are prioritized - the adapter's message may name them
      final var prioritizedIdsOfType = properties
          .getPrioritizedAdapters()
          .stream()
          .filter(adapterId -> servicesOfType
              .stream()
              .anyMatch(service -> service.getAdapterId().equals(adapterId)))
          .toList();
      servicesOfType
          .getFirst()
          .validateDistinctAdapterInstances(
              prioritizedIdsOfType.size() == servicesOfType.size()
                  ? prioritizedIdsOfType
                  : servicesOfType
                      .stream()
                      .map(AdapterDeploymentService::getAdapterId)
                      .toList());
    });

  }

  /**
   * Reports configured workflow IDs
   * (<code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;bpmnProcessId&gt;</code>)
   * matching no executable BPMN process found in the module's resources. Only a
   * WARN, consistent with the handling of configured workflow modules missing in the
   * classpath: the BPMN may arrive later (e.g. during a BPMS migration), so booting
   * must not be prevented. Runs after {@link #deployResources(List, BiFunction)}
   * processed all adapters because BPMN process IDs are known only after the
   * adapters' <code>readBpmn</code> - IDs found by ANY adapter count as known (the
   * resources location may differ per adapter).
   *
   * @param workflowModuleIds The workflow module IDs deployed
   * @param bpmnFilesByProcessId The BPMN file each executable process was found in,
   *          per workflow module
   */
  private void warnAboutConfiguredWorkflowsUnknownToBpmnResources(
      final List<String> workflowModuleIds,
      final Map<String, Map<String, String>> bpmnFilesByProcessId) {

    workflowModuleIds.forEach(workflowModuleId -> {
      final var workflowModule = properties.getWorkflowModules().get(workflowModuleId);
      if ((workflowModule == null) || workflowModule.getWorkflows().isEmpty()) {
        return;
      }
      final var knownProcessIds = bpmnFilesByProcessId.getOrDefault(workflowModuleId, Map.of()).keySet();
      final var unknownConfiguredWorkflows = workflowModule
          .getWorkflows()
          .keySet()
          .stream()
          .filter(bpmnProcessId -> !knownProcessIds.contains(bpmnProcessId))
          .sorted()
          .toList();
      if (unknownConfiguredWorkflows.isEmpty()) {
        return;
      }
      final var propPrefix = "\n  %s.workflow-modules.%s.workflows.".formatted(
          MigrationAdapterProperties.PREFIX,
          workflowModuleId);
      log.warn(
          """
              Found properties for BPMN processes
                {}.workflow-modules.{}.workflows.{}
              which were not found in any BPMN resource of workflow module '{}'! Fix the BPMN process ID
              or add the BPMN file. Two cases are intended: the BPMN arrives later (e.g. during a BPMS
              migration), and the id belongs to a BPMN process this application declares without
              deploying one - a renamed process, whose old id keeps the versions of it a BPMS still
              holds and which 'outfaded-versions' is configured for right here. Executable BPMN process
              IDs known for this workflow module are: {}.""",
          MigrationAdapterProperties.PREFIX,
          workflowModuleId,
          String.join(propPrefix, unknownConfiguredWorkflows),
          workflowModuleId,
          knownProcessIds.isEmpty()
              ? "none"
              : "'%s'".formatted(String.join("', '", knownProcessIds.stream().sorted().toList())));
    });

  }

  /**
   * Reports the executable BPMN processes of a workflow module which no
   * <code>&#64;WorkflowService</code> class claims. A BPMN file is deployed to the BPMS
   * as a whole, so a process drawn next to the one the application asked for travels
   * with it, and it used to end the boot: the wiring validation found no method for its
   * tasks and asked for a workflow service, which is the right sentence for a process
   * the application means to serve and the wrong one for a process it does not.
   * <p>
   * Only a WARN, because the boot cannot tell a forgotten workflow service from a
   * process which belongs to somebody else. What it costs is in the message, together
   * with the two ways out.
   *
   * @param workflowModuleIds The workflow module IDs deployed
   * @param bpmnFilesByProcessId The BPMN file each executable process was found in, per
   *          workflow module
   */
  private void reportBpmnProcessesWithoutWorkflowService(
      final List<String> workflowModuleIds,
      final Map<String, Map<String, String>> bpmnFilesByProcessId) {

    if (workflowTaskWiring == null) {
      return;
    }
    workflowModuleIds.forEach(workflowModuleId -> {
      final var unclaimedProcessIds = workflowTaskWiring
          .bpmnProcessesWithoutWorkflowService(workflowModuleId);
      if (unclaimedProcessIds.isEmpty()) {
        return;
      }
      final var filesByProcessId = bpmnFilesByProcessId.getOrDefault(workflowModuleId, Map.of());
      log.warn(
          """
              Workflow module '{}' deploys BPMN processes which no @WorkflowService class of this \
              application claims:{}
              They are deployed because a BPMN file travels to the BPMS as a whole, so a process \
              modelled next to the one you asked for goes with it. A workflow of such a process can \
              still be started, by a call activity of another process or by the BPMS itself, and it \
              will not get past its first task, because no @WorkflowTask method of this application \
              serves it. Either serve the process, by a class annotated with \
              @WorkflowService(bpmnProcess = @BpmnProcess(bpmnProcessId = "<the process>")) holding a \
              @WorkflowTask method per task, or take the process out of its file. Nothing to do here \
              if another application serves it.""",
          workflowModuleId,
          unclaimedProcessIds
              .stream()
              .map(bpmnProcessId -> "\n  - process '%s' of file '%s'".formatted(
                  bpmnProcessId,
                  filesByProcessId.getOrDefault(bpmnProcessId, "unknown")))
              .collect(java.util.stream.Collectors.joining()));
    });

  }

  /**
   * Deploys the resources of the given workflow module using the given adapter's
   * deployment service and remembers the resulting processing context for
   * {@link #startWorkflowProcessing(List)}.
   *
   * @param workflowModuleId The workflow module ID
   * @param deploymentService The deployment service to use
   * @param resourcesLoader Loads the files of one location and extension
   * @param bpmnFilesByProcessId Collects the file each executable BPMN process was
   *     found in (used by the reports running after all adapters were processed)
   * @param <PC> The processing context, used to store all information needed by the adapter to deploy the process.
   */
  private <PC> void deployResourcesOfAdapter(
      final String workflowModuleId,
      final AdapterDeploymentService<?, PC> deploymentService,
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader,
      final Map<String, String> bpmnFilesByProcessId) {

    // a configured location is the only one; the convention may name two (the
    // application IS the workflow module, and a module tested inside its own Maven
    // module is that as well while keeping its files below the module ID).
    // The first location holding files wins - never both, so a process cannot be
    // deployed twice.
    final var candidateLocations = properties.getAdapterResourcesLocationsFor(
        workflowModuleId,
        deploymentService.getAdapterId());
    var searchedLocation = candidateLocations.getFirst();
    var foundFiles = Map.<String, InputStream>of();
    for (final var candidate : candidateLocations) {
      final var filesOfCandidate = resourcesLoader.apply(candidate.location(), BPMN_EXTENSION);
      if (!filesOfCandidate.isEmpty()) {
        searchedLocation = candidate;
        foundFiles = filesOfCandidate;
        break;
      }
    }
    final var resourcesLocation = searchedLocation;
    final var bpmsProcessingContext = new BpmsProcessingContextHolder<PC>();
    final var bpmnFiles = foundFiles;
    try {
      bpmnFiles
          .entrySet()
          // ...and process them...
          .forEach(bpmnFileEntry -> processBpmn(
              workflowModuleId,
              deploymentService,
              bpmsProcessingContext.getBpmsProcessingContext(),
              bpmnFileEntry.getKey(), // filename
              bpmnFileEntry.getValue(), // InputStream
              resourcesLocation.vanillaBpBpmn(),
              bpmnFilesByProcessId)
              .ifPresentOrElse(
                  bpmsProcessingContext::setBpmsProcessingContext,
                  () -> log.warn(
                      "File '{}' of workflow module '{}' did not contain any executable processes. Skipping deployment of this file!",
                      bpmnFileEntry.getKey(),
                      workflowModuleId)));
    } finally {
      // streams are opened by the platform's resources loader and owned by this
      // pipeline: close ALL of them regardless of the processing outcome - also
      // the ones never processed because an earlier file failed (adapters must
      // not close them, see AdapterDeploymentService#readBpmn)
      bpmnFiles.forEach((
          filename,
          bpmn) -> {
        try {
          bpmn.close();
        } catch (final java.io.IOException e) {
          log.warn(
              "Could not close the stream of BPMN file '{}' of workflow module '{}'",
              filename,
              workflowModuleId,
              e);
        }
      });
    }

    // zero executable processes (empty/missing location or only non-executable
    // BPMN): warn with the key to change and skip this adapter for this module -
    // the adapter must never be called with a null processing context
    if (bpmsProcessingContext.getBpmsProcessingContext() == null) {
      log.warn(
          "No executable BPMN processes found for workflow module '{}' at location {}! "
              + "Adapter '{}' is skipped for this workflow module, so none of its workflows can be "
              + "started by that adapter, and any DMN file at that location is not deployed either "
              + "(a decision table travels with the processes calling it, never on its own). If "
              + "this is unintended, check property "
              + "'{}.workflow-modules.{}.adapters.{}.resources-location' (or '{}.resources-location') "
              + "and the BPMN files at that location.",
          workflowModuleId,
          candidateLocations.size() == 1
              ? "'%s'".formatted(resourcesLocation.location())
              : candidateLocations
                  .stream()
                  .map(candidate -> "'%s'".formatted(candidate.location()))
                  .collect(java.util.stream.Collectors.joining(" and ")),
          deploymentService.getAdapterId(),
          MigrationAdapterProperties.PREFIX,
          workflowModuleId,
          deploymentService.getAdapterId(),
          MigrationAdapterProperties.PREFIX);
      return;
    }

    // the decision tables of the module, into the context its processes produced - a
    // business rule task calls a decision of its own module, so both travel together
    processDmnFiles(
        workflowModuleId,
        deploymentService,
        resourcesLoader.apply(resourcesLocation.location(), DMN_EXTENSION),
        bpmsProcessingContext);

    // ...and finally deploy all the resources together (BPMN, DMN) to the BPMS
    deploymentService.deployResources(workflowModuleId, bpmsProcessingContext.getBpmsProcessingContext());
    bpmsProcessingContexts
        .computeIfAbsent(workflowModuleId, id -> new LinkedList<>())
        .add(ToBeStarted
            .<PC>builder()
            .deploymentService(deploymentService)
            .bpmsProcessingContext(bpmsProcessingContext.getBpmsProcessingContext())
            .build());

  }

  /**
   * Hands every DMN file of the workflow module to the adapter, which adds it to the
   * processing context so {@link AdapterDeploymentService#deployResources} deploys it
   * with the module's processes.
   * <p>
   * The streams are owned by this pipeline like the BPMN ones: they are closed here
   * whatever the adapter did with them, the ones after a failing file included.
   *
   * @param workflowModuleId The workflow module ID
   * @param deploymentService The adapter's deployment service
   * @param dmnFiles The DMN files found at the module's resources location for this adapter
   * @param bpmsProcessingContext The context the module's BPMN files produced
   * @param <PC> The processing context
   */
  private <PC> void processDmnFiles(
      final String workflowModuleId,
      final AdapterDeploymentService<?, PC> deploymentService,
      final Map<String, InputStream> dmnFiles,
      final BpmsProcessingContextHolder<PC> bpmsProcessingContext) {

    try {
      dmnFiles
          .forEach((
              filename,
              dmn) -> {
            log.debug(
                "Reading DMN file '{}' of workflow module '{}' for adapter '{}'",
                filename,
                workflowModuleId,
                deploymentService.getAdapterId());
            bpmsProcessingContext
                .setBpmsProcessingContext(
                    deploymentService
                        .readDmn(
                            workflowModuleId,
                            bpmsProcessingContext.getBpmsProcessingContext(),
                            filename,
                            dmn));
          });
    } finally {
      dmnFiles.forEach((
          filename,
          dmn) -> {
        try {
          dmn.close();
        } catch (final java.io.IOException e) {
          log.warn(
              "Could not close the stream of DMN file '{}' of workflow module '{}'",
              filename,
              workflowModuleId,
              e);
        }
      });
    }

  }

  /**
   * Process the given BPMN file.
   *
   * @param workflowModuleId The workflow module ID
   * @param deploymentService The deployment service to use
   * @param bpmsProcessingContext The context used to store all information needed by the adapter to deploy the process
   * @param filename The filename of the BPMN file (used for logging and error messages)
   * @param bpmn The BPMN resource inputstream
   * @param isVanillaBpBpmn Whether the BPMN is VanillaBP's BPMN or is specific to the adapter's BPMS
   * @param bpmnFilesByProcessId Collects the file each executable BPMN process was found in
   * @param <BPMN> The BPMN model type
   * @param <PC> The processing context, used to store all information needed by the adapter to deploy the process
   * @return The context used to store all information needed by the adapter to deploy the process
   */
  @SuppressWarnings("unchecked")
  private <BPMN, PC> Optional<PC> processBpmn(
      final String workflowModuleId,
      final AdapterDeploymentService<BPMN, PC> deploymentService,
      final PC bpmsProcessingContext,
      final String filename,
      final InputStream bpmn,
      final boolean isVanillaBpBpmn,
      final Map<String, String> bpmnFilesByProcessId) {

    // read executable processes from the BPMN file
    final var executableProcesses = deploymentService.readBpmn(
        workflowModuleId,
        filename,
        bpmn,
        isVanillaBpBpmn);
    if (executableProcesses.isEmpty()) {
      return Optional.empty();
    }

    // process each executable process, threading the processing context accumulated
    // so far through ALL processes of the file...
    var context = bpmsProcessingContext;
    for (final var processIdAndModel : executableProcesses) {
      final var bpmnProcessId = processIdAndModel.getKey();
      final var bpmnModel = processIdAndModel.getValue();
      // the first adapter finding the process names the file: two adapters may read
      // the same module from locations of their own, and the message wants one name
      bpmnFilesByProcessId.putIfAbsent(bpmnProcessId, filename);
      // ...preparing the model...
      context = deploymentService.prepareBpmn(
          workflowModuleId,
          context,
          filename,
          bpmnProcessId,
          bpmnModel);
      if (context == null) {
        throw new IllegalStateException(
            """
                Adapter '%s' returned a null processing context from prepareBpmn for BPMN process '%s' \
                of file '%s' of workflow module '%s'! prepareBpmn must always return a non-null \
                context - it is threaded through the whole deployment pipeline."""
                .formatted(
                    deploymentService.getAdapterId(),
                    bpmnProcessId,
                    filename,
                    workflowModuleId));
      }
      // ...wire the business code to the BPMN's tasks...
      deploymentService.wireBpmn(
          workflowModuleId,
          filename,
          bpmnProcessId,
          bpmnModel,
          context);
      // ...and do wiring for all matching extensions wiring services found:
      // matching uses DECLARED-type assignability - the same rule as
      // startWorkflowProcessing/stopWorkflowProcessing - so an extension is either
      // consistently wired AND started or consistently neither
      final var currentContext = context;
      wiringServices
          .stream()
          .filter(wiringService -> wiringService.getModelType()
              .isAssignableFrom(deploymentService.getModelType()))
          .filter(wiringService -> wiringService.getProcessContextType()
              .isAssignableFrom(deploymentService.getProcessContextType()))
          .map(wiringService -> (ExtensionWiringService<BPMN, PC>) wiringService)
          .forEach(wiringService -> wiringService.wireBpmn(
              workflowModuleId,
              filename,
              bpmnProcessId,
              bpmnModel,
              currentContext));
    }
    return Optional.of(context);

  }

  /**
   * Starts to running the workflows of the given BPMN processes.
   *
   * @param workflowModuleIds The workflow module IDs to deploy
   * @param <PC> The processing context, used to store all information needed by the adapter to deploy the process.
   */
  @SuppressWarnings("unchecked")
  public <BPMN, PC> void startWorkflowProcessing(
      final List<String> workflowModuleIds) {

    // walk through all workflow modules...
    workflowModuleIds
        .forEach(workflowModuleId -> {
          final var toBeStarted = this.bpmsProcessingContexts.get(workflowModuleId);
          if (toBeStarted == null) {
            return;
          }
          // ...and all adapters resources were deployed to (for BPMS migration all of them keep processing)...
          toBeStarted
              .forEach(bpmsProcessingContext -> {
                final var deploymentService = (AdapterDeploymentService<?, PC>) bpmsProcessingContext.deploymentService;
                final var processingContext = (PC) bpmsProcessingContext.getBpmsProcessingContext();
                // ...and start workflow processing for each adapter
                deploymentService
                    .startWorkflowProcessing(
                        workflowModuleId,
                        processingContext);
              });
        });

    // walk through all workflow modules...
    workflowModuleIds
        .forEach(workflowModuleId -> {
          final var toBeStarted = this.bpmsProcessingContexts.get(workflowModuleId);
          if (toBeStarted == null) {
            return;
          }
          // ...and all adapters resources were deployed to...
          toBeStarted
              .forEach(bpmsProcessingContext -> {
                final var deploymentService = (AdapterDeploymentService<?, PC>) bpmsProcessingContext.deploymentService;
                final var processingContext = (PC) bpmsProcessingContext.getBpmsProcessingContext();
                // ...and start workflow processing for each extension
                wiringServices
                    .stream()
                    .filter(wiringService -> wiringService.getModelType()
                        .isAssignableFrom(deploymentService.getModelType()))
                    .filter(wiringService -> wiringService.getProcessContextType()
                        .isAssignableFrom(deploymentService.getProcessContextType()))
                    .map(wiringService -> (ExtensionWiringService<BPMN, PC>) wiringService)
                    .forEach(wiringService -> wiringService.startWorkflowProcessing(
                        workflowModuleId,
                        processingContext));
              });
        });

  }

  /**
   * Stops running the workflows of the given BPMN processes. This is the counterpart
   * of {@link #startWorkflowProcessing(List)} and is executed in reverse order:
   * extensions are stopped first (in reverse wiring order), then the adapters —
   * mirroring the start sequence where adapters are started before extensions.
   *
   * @param workflowModuleIds The workflow module IDs to stop
   * @param <PC> The processing context, used to store all information needed by the adapter to deploy the process.
   */
  @SuppressWarnings("unchecked")
  public <BPMN, PC> void stopWorkflowProcessing(
      final List<String> workflowModuleIds) {

    final var reversedWiringServices = new LinkedList<>(wiringServices);
    Collections.reverse(reversedWiringServices);

    final var reversedWorkflowModuleIds = new LinkedList<>(workflowModuleIds);
    Collections.reverse(reversedWorkflowModuleIds);

    // walk through all workflow modules (in reverse order)...
    reversedWorkflowModuleIds
        .forEach(workflowModuleId -> {
          final var toBeStopped = this.bpmsProcessingContexts.get(workflowModuleId);
          if (toBeStopped == null) {
            return;
          }
          // ...and all adapters resources were deployed to...
          toBeStopped
              .forEach(bpmsProcessingContext -> {
                final var deploymentService = (AdapterDeploymentService<?, PC>) bpmsProcessingContext.deploymentService;
                final var processingContext = (PC) bpmsProcessingContext.getBpmsProcessingContext();
                // ...and stop workflow processing for each extension first (reverse of start)
                reversedWiringServices
                    .stream()
                    .filter(wiringService -> wiringService.getModelType()
                        .isAssignableFrom(deploymentService.getModelType()))
                    .filter(wiringService -> wiringService.getProcessContextType()
                        .isAssignableFrom(deploymentService.getProcessContextType()))
                    .map(wiringService -> (ExtensionWiringService<BPMN, PC>) wiringService)
                    .forEach(wiringService -> wiringService.stopWorkflowProcessing(
                        workflowModuleId,
                        processingContext));
              });
        });

    // walk through all workflow modules (in reverse order)...
    reversedWorkflowModuleIds
        .forEach(workflowModuleId -> {
          final var toBeStopped = this.bpmsProcessingContexts.get(workflowModuleId);
          if (toBeStopped == null) {
            return;
          }
          // ...and stop workflow processing for each adapter
          toBeStopped
              .forEach(bpmsProcessingContext -> {
                final var deploymentService = (AdapterDeploymentService<?, PC>) bpmsProcessingContext.deploymentService;
                final var processingContext = (PC) bpmsProcessingContext.getBpmsProcessingContext();
                deploymentService
                    .stopWorkflowProcessing(
                        workflowModuleId,
                        processingContext);
              });
        });

  }

}
