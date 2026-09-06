package io.vanillabp.integration.test.mixed;

import java.util.Collection;
import java.util.List;

import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Stands in for the BPMN models of this application: both processes have one task,
 * wired to the <code>processTask</code> handler of their workflow service.
 */
@ApplicationScoped
public class MixedTaskWiringSource implements DummyTaskWiringSource {

  @Override
  public Collection<BpmnTaskSpec> tasksOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return List.of(new BpmnTaskSpec("Activity_Process", "processTask"));

  }

}
