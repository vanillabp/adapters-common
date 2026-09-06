package io.vanillabp.integration.test.deployment;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import io.vanillabp.bpmsdouble.DummyProcessVersionSource;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Stands in for the BPMS query a real adapter runs to learn the deployed versions of
 * a process, and counts how often it ran - the query belongs to the startup and to
 * versions never seen before, not to every task delivery.
 */
@ApplicationScoped
public class VersionedProcessVersionSource implements DummyProcessVersionSource {

  private final List<DeployedProcessVersion> versions = new CopyOnWriteArrayList<>(
      List.of(
          DeployedProcessVersion.of("1", null),
          DeployedProcessVersion.of("2", null),
          DeployedProcessVersion.of("3", null),
          DeployedProcessVersion.of("4", "release-2026")));

  private final AtomicInteger queries = new AtomicInteger();

  @Override
  public List<DeployedProcessVersion> versionsOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    queries.incrementAndGet();
    return List.copyOf(versions);

  }

  /**
   * @return How often the "BPMS" was asked
   */
  public int getQueries() {

    return queries.get();

  }

  /**
   * Mimics another cluster node deploying a new version and moving the version tag
   * to it - what a rolling deployment does while this node is running.
   *
   * @param version The version the other node deployed
   * @param versionTag The tag it carries now
   */
  public void deployedElsewhere(
      final String version,
      final String versionTag) {

    versions.replaceAll(
        known -> versionTag.equals(known.versionTag())
            ? DeployedProcessVersion.of(known.version(), null)
            : known);
    versions.add(DeployedProcessVersion.of(version, versionTag));

  }

}
