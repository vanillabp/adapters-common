package io.vanillabp.integration.runtime.deployment;

import java.util.List;
import java.util.stream.Stream;

import io.quarkus.runtime.StartupEvent;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.deployment.DeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import io.vanillabp.integration.runtime.processservice.ProcessServiceBaseCdiBean;
import io.vanillabp.spi.process.ProcessService;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the core deployment pipeline at boot - the Quarkus counterpart of the Spring
 * Boot integration's <code>SpringBootDeploymentService</code>: for every workflow
 * module and every prioritized adapter the pipeline
 * <code>readBpmn &rarr; prepareBpmn &rarr; wireBpmn &rarr; deployResources &rarr;
 * startWorkflowProcessing</code> is executed, honoring the
 * <code>vanillabp.adapters.&lt;id&gt;.deployment-failure</code> policy and wiring
 * extensions ordered by <code>getOrder()</code> (all of this lives in the core
 * {@link DeploymentService}).
 * <p>
 * <b>Lifecycle semantics compared to Spring Boot</b> (see also the module README):
 * Spring deploys during context refresh (<code>SmartLifecycle.start()</code>) and
 * starts workflow processing on <code>ApplicationReadyEvent</code>, i.e. after the
 * web server started serving. Quarkus' idiomatic hook is the {@link StartupEvent},
 * which fires before the application accepts traffic - so on Quarkus both
 * deployment and start of workflow processing happen <i>before</i> serving. The
 * invariant shared by both platforms: the phase-two outbox dispatchers only start
 * dispatching (including crash recovery) <i>after</i> workflow processing started -
 * enforced by {@link #STARTUP_PRIORITY} vs.
 * {@link #OUTBOX_DISPATCHER_STARTUP_PRIORITY} here and by
 * <code>@Order</code>-ed <code>ApplicationReadyEvent</code> listeners on Spring.
 * <p>
 * Adapters' {@link AdapterDeploymentService} beans are collected as element beans
 * plus flattened beans of type
 * <code>List&lt;AdapterDeploymentService&lt;Object, Object&gt;&gt;</code> (one
 * instance per configured adapter id) - the same per-adapter-id convention as for
 * the process services. Extensions contribute plain {@link ExtensionWiringService}
 * element beans.
 */
@Slf4j
@ApplicationScoped
public class VanillaBpDeploymentRunner {

  /**
   * Priority of the deployment runner's {@link StartupEvent} observer: AFTER the
   * default-priority observers (the process-service startup validations, e.g.
   * unserved-prioritized-adapter and phase-two-outbox checks, fire BEFORE anything
   * is deployed - Spring Boot parity: validation at
   * <code>SmartInitializingSingleton</code>, deployment at
   * <code>SmartLifecycle.start()</code>), but BEFORE the phase-two outbox
   * dispatchers ({@link #OUTBOX_DISPATCHER_STARTUP_PRIORITY}) - a crash-recovered
   * phase-two operation must never be dispatched before the deployment pipeline
   * deployed the resources and started workflow processing.
   */
  public static final int STARTUP_PRIORITY = Interceptor.Priority.APPLICATION + 600;

  /**
   * Priority of the phase-two outbox dispatchers' {@link StartupEvent} observers:
   * after the deployment runner ({@link #STARTUP_PRIORITY}).
   */
  public static final int OUTBOX_DISPATCHER_STARTUP_PRIORITY = Interceptor.Priority.APPLICATION + 700;

  @Inject
  MigrationAdapterProperties properties;

  @Inject
  BpmsResourceIndex bpmsResourceIndex;

  /**
   * Adapters' deployment services as <i>element</i> beans (one bean per adapter).
   */
  @Inject
  @Any
  Instance<AdapterDeploymentService<?, ?>> adapterDeploymentServices;

  /**
   * Additionally accepted shape: beans of type
   * <code>List&lt;AdapterDeploymentService&lt;Object, Object&gt;&gt;</code>,
   * flattened into the collected deployment services - one instance per configured
   * adapter id (a CDI producer cannot yield N element beans for N
   * runtime-configured ids). Both element type parameters are LITERALLY
   * {@code Object} by convention, regardless of the adapter's actual model and
   * context classes (CDI's parameterized-type matching of differing type arguments
   * is not reliable across modes, so the platform looks the beans up with the exact
   * type - the pipeline matches models via {@code getModelType()} /
   * {@code getProcessContextType()}, never via the generics).
   */
  @Inject
  @Any
  Instance<List<AdapterDeploymentService<Object, Object>>> adapterDeploymentServiceLists;

  /**
   * Extensions' wiring services (element beans). Adapters may show up here, too,
   * since {@link AdapterDeploymentService} extends {@link ExtensionWiringService} -
   * the core {@link DeploymentService} filters them out.
   */
  @Inject
  @Any
  Instance<ExtensionWiringService<?, ?>> extensionWiringServices;

  /**
   * All generated process-service beans - stopped on graceful shutdown (parity with
   * Spring Boot's <code>SmartLifecycle.stop()</code>).
   */
  @Inject
  @Any
  Instance<ProcessService<?>> processServices;

  /**
   * The core's wiring interface - handed to the {@link DeploymentService} for what
   * belongs to a workflow module as a whole and is answered once the last adapter of
   * the module finished deploying: the deployed processes no
   * <code>&#64;WorkflowService</code> class claims, the versions a BPMS still holds for
   * a process id the application only declares, the
   * <code>&#64;WorkflowTask</code> methods serving no task at all and the version tags
   * the annotations name. The core runs them rather than leaving them to an adapter
   * which can forget them.
   */
  @Inject
  io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry workflowTaskWiring;

  private DeploymentService deploymentService;

  private List<String> workflowModuleIds;

  private volatile boolean running = false;

  /**
   * Builds the core {@link DeploymentService}, deploys the BPMN resources of all
   * workflow modules found in the classpath and starts workflow processing.
   *
   * @param event The startup event observed
   */
  void onStart(
      @Observes
      @Priority(STARTUP_PRIORITY) final StartupEvent event) {

    deployAndStart();

  }

  synchronized void deployAndStart() {

    workflowModuleIds = bpmsResourceIndex.getWorkflowModuleIds();

    final List<AdapterDeploymentService<?, ?>> deploymentServices = Stream
        .concat(
            adapterDeploymentServices.stream(),
            adapterDeploymentServiceLists
                .stream()
                .filter(java.util.Objects::nonNull)
                .flatMap(List::stream))
        .filter(java.util.Objects::nonNull)
        .<AdapterDeploymentService<?, ?>>map(service -> service)
        .toList();
    final List<ExtensionWiringService<?, ?>> wiringServices = extensionWiringServices
        .stream()
        .toList();

    deploymentService = new DeploymentService(
        properties, deploymentServices, wiringServices, workflowTaskWiring);

    log.info("Deploying BPMN resources of workflow modules: {}", workflowModuleIds);
    deploymentService.deployResources(
        workflowModuleIds,
        bpmsResourceIndex::loadResources);

    // the election capability of the prioritized adapters is checked once the adapters
    // deployed - only then does an adapter know what its BPMS can do - and before
    // anything touches a workflow
    processServices
        .stream()
        .filter(ProcessServiceBaseCdiBean.class::isInstance)
        .map(processService -> (ProcessServiceBaseCdiBean<?>) processService)
        // per DECLARED BPMN process id: which adapters serve a workflow is configurable per
        // workflow, so a secondary or declared-only id may be the one whose combination
        // cannot locate its workflows
        .flatMap(processService -> processService.getProcessServicesOfDeclaredIds().stream())
        .forEach(processService -> processService.validateElectionCapabilityAfterDeployment());

    deploymentService.startWorkflowProcessing(workflowModuleIds);

    running = true;

  }

  /**
   * Stops workflow processing of adapters and extensions in reverse start order and
   * stops all process services - invoked on graceful shutdown by
   * {@link VanillaBpShutdownObserver}. Safe to call more than once (subsequent
   * calls are no-ops).
   */
  public synchronized void stop() {

    if (!running) {
      return;
    }

    log.info("Stopping workflow processing of workflow modules: {}", workflowModuleIds);
    deploymentService.stopWorkflowProcessing(workflowModuleIds);

    processServices
        .stream()
        .filter(ProcessServiceBaseCdiBean.class::isInstance)
        .map(processService -> (ProcessServiceBaseCdiBean<?>) processService)
        .forEach(ProcessServiceBaseCdiBean::stopService);

    running = false;

  }

}
