package io.vanillabp.integration.adapter.migration.deployment;

import java.util.Collection;
import java.util.List;

/**
 * What a platform integration can add to the report about the BPMN processes of a workflow
 * module which no <code>&#64;WorkflowService</code> class claims - one sentence per finding,
 * written into the report's own WARN block rather than as a warning of its own.
 * <p>
 * It exists because the answer is platform-specific while the report is not. On Spring Boot a
 * workflow service is found because it is a bean (decision 21 in the repository's
 * DECISIONS.md), so a class carrying the annotation which nobody made a bean of is not in the
 * bean definitions at all and cannot be told from a class another profile brings - the boot
 * says nothing about it while the process it declares goes unserved. Looking for such a class
 * means reading class resources, which is Spring's work and must not enter the core. On
 * Quarkus the same case fails the BUILD, because the build knows its bean set, so that
 * platform contributes nothing here.
 * <p>
 * Asked ONLY where a process is actually being reported, which is what keeps a healthy boot
 * free of the cost: no unclaimed process, no report, no question.
 * <p>
 * An implementation neither throws nor decides anything. It says what it found, and the
 * deployment writes it next to the processes it is already reporting.
 */
@FunctionalInterface
public interface UnclaimedBpmnProcessHints {

  /**
   * @param workflowModuleId The workflow module being reported
   * @param bpmnProcessIds The BPMN process ids of that module nothing claims
   * @return One sentence per finding, in the order they should be read; empty where there is
   *         nothing to add
   */
  List<String> whatElseIsWorthSaying(
      String workflowModuleId,
      Collection<String> bpmnProcessIds);

}
