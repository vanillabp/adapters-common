package io.vanillabp.bpmsdouble;

import java.util.List;

import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;

/**
 * Optional hook of the dummy adapter used by integration tests to supply the deployed
 * versions of a BPMN process: the dummy adapter has no BPMS to ask, so a bean of this
 * type stands in for what a real adapter queries from its BPMS. If a bean is present,
 * the dummy adapter registers a version catalog with the core
 * ({@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#registerProcessVersions}),
 * which is what makes <code>version = "release-2024"</code> work; without a bean the
 * dummy adapter reports no versions at all, like a BPMS which cannot tell.
 */
@FunctionalInterface
public interface DummyProcessVersionSource {

  /**
   * The deployed versions of the given BPMN process, oldest first.
   *
   * @param adapterId The adapter ID being asked
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return The deployed versions
   */
  List<DeployedProcessVersion> versionsOf(
      String adapterId,
      String workflowModuleId,
      String bpmnProcessId);

}
