package io.vanillabp.integration.adapter.migration.workflowtask;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog;

/**
 * What the BPMS of every adapter knows about the deployed versions of the BPMN
 * processes, per (workflow module, BPMN process) - registered by the adapters during
 * <code>wireBpmn</code> (see
 * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#registerProcessVersions})
 * and used to place a version TAG named by <code>&#64;WorkflowTask(version = ...)</code>
 * and its siblings in the deployment order.
 * <p>
 * A version specification made of numbers never gets here: it is compared to the
 * version the adapter reported. That is what keeps the cost of the feature at zero for
 * applications not using version tags.
 * <p>
 * While a BPMS migration is running, two adapters may serve the same BPMN process. Each
 * BPMS counts its own versions, so the catalogs are asked in registration order and the
 * first one knowing the version or tag answers.
 * <p>
 * What a version is, and why overlapping ranges end the start, is decision 20 in the repository's
 * DECISIONS.md.
 */
public class ProcessVersions {

  private static final Logger log = LoggerFactory.getLogger(ProcessVersions.class);

  private record RegistryKey(
                             String workflowModuleId,
                             String bpmnProcessId) {
  }

  /**
   * One BPMS answering for one BPMN process.
   *
   * @param adapterId The adapter ID
   * @param catalog What that BPMS knows about the process' versions
   */
  public record RegisteredCatalog(
                                  String adapterId,
                                  ProcessVersionCatalog catalog) {
  }

  private record DeploymentKey(
                               String adapterId,
                               String workflowModuleId,
                               String bpmnProcessId) {
  }

  private final Map<RegistryKey, List<RegisteredCatalog>> catalogs = new ConcurrentHashMap<>();

  /**
   * The version each adapter deployed during THIS boot - the border between
   * "the model this application brings" and the older versions the BPMS still holds.
   */
  private final Map<DeploymentKey, String> deployedVersions = new ConcurrentHashMap<>();

  /**
   * The version identifiers and tags already reported as unknown - a task delivery
   * must not log the same message over and over.
   */
  private final java.util.Set<String> reportedAsUnknown = ConcurrentHashMap.newKeySet();

  /**
   * Registers what one BPMS knows about one BPMN process. Registering the same catalog
   * again (a module deployed at every boot, several workflow service classes) does not
   * duplicate it.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param catalog The versions of that process
   */
  public void register(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final ProcessVersionCatalog catalog) {

    if (catalog == null) {
      return;
    }
    final var registered = catalogs
        .computeIfAbsent(
            new RegistryKey(workflowModuleId, bpmnProcessId),
            key -> new CopyOnWriteArrayList<>());
    if (registered
        .stream()
        .noneMatch(existing -> existing.adapterId().equals(adapterId) && (existing.catalog() == catalog))) {
      registered.add(new RegisteredCatalog(adapterId, catalog));
    }

  }

  /**
   * Remembers the version an adapter deployed during this boot - see
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#registerDeployedVersion}.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param version The version identifier the BPMS assigned, or <code>null</code>
   */
  public void recordDeployedVersion(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    if (version == null) {
      return;
    }
    deployedVersions.put(new DeploymentKey(adapterId, workflowModuleId, bpmnProcessId), version);

  }

  /**
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @return The version that adapter deployed during this boot, or <code>null</code>
   */
  public String deployedVersion(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return deployedVersions.get(new DeploymentKey(adapterId, workflowModuleId, bpmnProcessId));

  }

  /**
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @return The BPMS answering for that process, in registration order
   */
  public List<RegisteredCatalog> registeredCatalogs(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var registered = catalogs.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    return registered == null
        ? List.of()
        : List.copyOf(registered);

  }

  /**
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @return Resolves version identifiers and version tags of that BPMN process
   */
  public VersionRange.ProcessVersionResolver resolverFor(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return versionOrVersionTag -> resolve(workflowModuleId, bpmnProcessId, versionOrVersionTag);

  }

  private io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion resolve(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String versionOrVersionTag) {

    final var resolved = lookup(workflowModuleId, bpmnProcessId, versionOrVersionTag);
    if (resolved != null) {
      return resolved;
    }
    final var registered = catalogs.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    reportUnknown(workflowModuleId, bpmnProcessId, versionOrVersionTag, (registered == null) || registered.isEmpty());
    return null;

  }

  /**
   * Asks the catalogs without reporting anything - the startup check reports in its own
   * words, naming the method whose specification cannot be served.
   */
  private io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion lookup(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String versionOrVersionTag) {

    final var registered = catalogs.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if ((registered != null) && !registered.isEmpty()) {
      // asking a catalog may query the BPMS: a version deployed by ANOTHER cluster
      // node is unknown here until it is asked for (the catalogs cache the answer)
      for (final var candidate : registered) {
        final var resolved = candidate
            .catalog()
            .resolveVersion(workflowModuleId, bpmnProcessId, versionOrVersionTag);
        if (resolved != null) {
          return resolved;
        }
      }
    }
    return null;

  }

  /**
   * Loads all versions of the given BPMN process the BPMS knows - called at startup
   * for the processes whose annotations name a version tag, so the tags are resolved
   * before the first workflow needs them.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   */
  public void warmUp(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var registered = catalogs.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if (registered == null) {
      return;
    }
    registered
        .forEach(candidate -> {
          final var versions = candidate.catalog().deployedVersionsOf(workflowModuleId, bpmnProcessId);
          log.debug(
              "Adapter '{}' knows {} deployed version(s) of BPMN process '{}' of workflow module '{}': {}",
              candidate.adapterId(),
              versions == null
                  ? 0
                  : versions.size(),
              bpmnProcessId,
              workflowModuleId,
              versions);
        });

  }

  /**
   * Reports a version tag no BPMS knows, ONCE, naming what the developer can do about
   * it. Not a boot failure: the tagged version may be deployed later (a rolling
   * deployment where another cluster node is ahead), and an application whose other
   * methods serve the deployed versions has to keep running.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param versionTag The version tag named by an annotation
   * @param describedLocation The method serving that specification, plus the
   *          declaration it came from where the method names none itself
   */
  public void reportUnknownVersionTag(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String versionTag,
      final String describedLocation) {

    if (lookup(workflowModuleId, bpmnProcessId, versionTag) != null) {
      return;
    }
    log.warn(
        """
            The version specification '{}' of {} names a version tag no BPMS knows for BPMN \
            process '{}' of workflow module '{}'! Until a version tagged that way is deployed, \
            that method serves no workflow. Check the tag against the BPMN model (Camunda 7: \
            'camunda:versionTag', Camunda 8: 'zeebe:versionTag') - a version specification made \
            of numbers (e.g. '>2') needs no tag at all.""",
        versionTag,
        describedLocation,
        bpmnProcessId,
        workflowModuleId);

  }

  private void reportUnknown(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String versionOrVersionTag,
      final boolean withoutCatalog) {

    final var key = "%s|%s|%s".formatted(workflowModuleId, bpmnProcessId, versionOrVersionTag);
    if (!reportedAsUnknown.add(key)) {
      return;
    }
    if (withoutCatalog) {
      // saying "no versions are deployed" here would be wrong: the BPMS may well hold
      // versions of this process - what is missing is an adapter able to ASK about
      // them, which is a different defect with a different remedy
      log.warn(
          """
              The version specifications of the methods serving BPMN process '{}' of workflow \
              module '{}' name '{}', but no adapter of this application can be asked which \
              versions of that process its BPMS holds! Version specifications made of numbers \
              (e.g. '1-3', '>2') work on every BPMS which reports the version of a process - \
              version TAGS need a BPMS which can be asked about them.""",
          bpmnProcessId,
          workflowModuleId,
          versionOrVersionTag);
      return;
    }
    log.warn(
        """
            Neither a deployed version nor a version tag '{}' of BPMN process '{}' of workflow \
            module '{}' is known to any BPMS - version specifications naming it match nothing \
            until it is deployed.""",
        versionOrVersionTag,
        bpmnProcessId,
        workflowModuleId);

  }

}
