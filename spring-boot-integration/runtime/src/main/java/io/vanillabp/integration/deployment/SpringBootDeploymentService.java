package io.vanillabp.integration.deployment;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import io.vanillabp.integration.adapter.migration.deployment.DeploymentService;
import io.vanillabp.integration.processservice.ProcessServiceSpringBean;
import io.vanillabp.integration.workflowmodule.WorkflowModule;
import io.vanillabp.integration.workflowmodule.WorkflowModules;
import io.vanillabp.spi.process.ProcessService;
import lombok.RequiredArgsConstructor;

/**
 * Manages deployment of resources using {@link DeploymentService}.
 * <p>
 * The service participates in the application lifecycle as a
 * {@link SmartLifecycle}:
 * <ul>
 *   <li>{@link #start()} loads and deploys all BPMN resources — during context
 *       refresh, after all singletons were created but before the application is
 *       marked as started;</li>
 *   <li>{@code startWorkflowProcessing} is triggered by
 *       {@link ApplicationReadyEvent} (i.e. only once the application is fully
 *       ready to process workflows);</li>
 *   <li>{@link #stop()} stops workflow processing of adapters/extensions and all
 *       {@link ProcessServiceSpringBean}s on graceful shutdown.</li>
 * </ul>
 * <b>Phase:</b> {@link SmartLifecycle#DEFAULT_PHASE} ({@code Integer.MAX_VALUE}) is
 * used deliberately: on shutdown, lifecycle beans are stopped in descending phase
 * order, so workflow processing stops in the very first group — before Spring
 * Boot's web server graceful shutdown ({@code SmartLifecycle.DEFAULT_PHASE - 1024})
 * and before messaging listener containers. This way no new workflow jobs are
 * processed while the infrastructure they may depend on is being torn down.
 */
@RequiredArgsConstructor
public class SpringBootDeploymentService implements SmartLifecycle {

  /**
   * Order of the {@link ApplicationReadyEvent} listener starting workflow
   * processing: BEFORE the phase-two outbox dispatchers' pollers
   * ({@link #OUTBOX_DISPATCHER_LISTENER_ORDER}) - a crash-recovered phase-two
   * operation must never be dispatched before workflow processing started (the
   * same invariant is enforced on Quarkus via {@code StartupEvent} observer
   * priorities).
   */
  public static final int START_PROCESSING_LISTENER_ORDER = 0;

  /**
   * Order of the phase-two outbox dispatchers' {@link ApplicationReadyEvent}
   * listeners: after {@link #START_PROCESSING_LISTENER_ORDER}.
   */
  public static final int OUTBOX_DISPATCHER_LISTENER_ORDER = START_PROCESSING_LISTENER_ORDER + 100;

  private final DeploymentService deploymentService;

  private final WorkflowModules allWorkflowModules;

  private final ObjectProvider<ProcessService<?>> processServices;

  private volatile boolean running = false;

  /**
   * Triggers loading of all resources of the workflow modules - their BPMN files and
   * their decision tables - and deployment of them.
   */
  @Override
  public void start() {

    deploymentService.deployResources(
        getWorkflowModuleIds(),
        this::resourcesLoader);

    running = true;

  }

  /**
   * Stops processing of workflows on graceful shutdown: adapters and extensions are
   * notified in reverse start order, afterwards all process services are stopped.
   */
  @Override
  public void stop() {

    deploymentService.stopWorkflowProcessing(
        getWorkflowModuleIds());

    processServices
        .stream()
        .filter(ProcessServiceSpringBean.class::isInstance)
        .map(processService -> (ProcessServiceSpringBean<?>) processService)
        .forEach(ProcessServiceSpringBean::stopService);

    running = false;

  }

  @Override
  public boolean isRunning() {

    return running;

  }

  /**
   * @see SpringBootDeploymentService class comment on the chosen phase
   */
  @Override
  public int getPhase() {

    return SmartLifecycle.DEFAULT_PHASE;

  }

  /**
   * Start processing of workflows one the application started. Ordered BEFORE the
   * phase-two outbox dispatchers' listeners (see
   * {@link #START_PROCESSING_LISTENER_ORDER}).
   * <p>
   * The election capability of the prioritized adapters is checked right before that:
   * an adapter only knows what its BPMS can do once it deployed, and nothing has
   * touched a workflow yet at this point.
   */
  @Order(START_PROCESSING_LISTENER_ORDER)
  @EventListener(ApplicationReadyEvent.class)
  public void startProcessingOfWorkflows() {

    processServices
        .stream()
        .filter(ProcessServiceSpringBean.class::isInstance)
        .map(processService -> (ProcessServiceSpringBean<?>) processService)
        // per DECLARED BPMN process id: which adapters serve a workflow is configurable per
        // workflow, so a secondary or declared-only id may be the one whose combination
        // cannot locate its workflows
        .flatMap(processService -> processService.getProcessServicesOfDeclaredIds().stream())
        .forEach(processService -> processService.validateElectionCapabilityAfterDeployment());

    deploymentService.startWorkflowProcessing(
        getWorkflowModuleIds());

  }

  private List<String> getWorkflowModuleIds() {

    return allWorkflowModules
        .getWorkflowModules()
        .stream()
        .map(WorkflowModule::getId)
        .toList();

  }

  /**
   * Recursively reads all resources of the given extension from the given location.
   *
   * @param resourceLocation The location to read from
   * @param extension The file extension to read, e.g. <code>.bpmn</code>
   * @return A map of relative paths to resources
   */
  public Map<String, InputStream> resourcesLoader(
      final String resourceLocation,
      final String extension) {

    final var resolver = new PathMatchingResourcePatternResolver();

    final var normalizedLocation = resourceLocation.endsWith("/")
        ? resourceLocation
        : resourceLocation
            + "/";

    final var resourcePattern = normalizedLocation
        + "**/*"
        + extension;

    final Resource[] resources;
    try {
      resources = resolver.getResources(resourcePattern);
    } catch (final IOException e) {
      throw new IllegalStateException(
          "Failed to resolve '%s' resources from location: %s".formatted(extension, resourceLocation), e);
    }

    final var result = new HashMap<String, InputStream>();

    for (final var resource : resources) {
      if (!resource.isReadable()) {
        continue;
      }

      final URI uri;
      try {
        uri = resource.getURI();
      } catch (final IOException e) {
        throw new IllegalStateException("Failed to resolve URI for resource", e);
      }

      final String relativePath = extractRelativePath(uri, normalizedLocation);
      if (relativePath == null) {
        continue;
      }

      try {
        result.put(relativePath, resource.getInputStream());
      } catch (final IOException e) {
        throw new IllegalStateException(
            "Failed to open InputStream for resource: "
                + relativePath, e
        );
      }
    }

    return result;

  }

  private String extractRelativePath(
      final URI uri,
      final String resourceLocation) {

    final var uriString = uri.toString();

    /*
     * Examples:
     * jar:file:/app.jar!/BOOT-INF/classes/bpmn/order/process.bpmn
     * file:/opt/app/bpmn/order/process.bpmn
     * classpath:/bpmn/order/process.bpmn
     */

    final var locationPath = resourceLocation.replace("classpath*:", "")
        .replace("classpath:", "")
        .replace("file:", "");

    final var index = uriString.indexOf(locationPath);
    if (index < 0) {
      return null;
    }

    // keep subdirectories: same-named BPMN files in different subdirectories of the
    // resources-location must not overwrite each other in the result map
    final var relative = uriString.substring(index + locationPath.length())
        .replace("\\", "/");

    return relative;

  }

}
