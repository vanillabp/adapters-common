package io.vanillabp.integration.test.persistence;

import java.util.Collection;
import java.util.List;

import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Stands in for the BPMN model of all applications of this module: 'TestProcess' has
 * exactly one task, wired to the <code>processTask</code> handler every workflow
 * service here declares.
 */
@ApplicationScoped
public class SingleTaskWiringSource implements DummyTaskWiringSource {

  @Override
  public Collection<BpmnTaskSpec> tasksOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return "TestProcess".equals(bpmnProcessId)
        ? List.of(new BpmnTaskSpec("Activity_Process", "processTask"))
        : List.of();

  }

}
