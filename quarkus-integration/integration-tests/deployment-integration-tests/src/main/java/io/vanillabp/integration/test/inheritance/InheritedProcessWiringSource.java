package io.vanillabp.integration.test.inheritance;

import java.util.Collection;
import java.util.List;

import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Stands in for the BPMN model of the process the inheriting workflow service serves: both
 * tasks belong to it, the one whose handler the class writes itself and the one whose
 * handler it inherits.
 */
@ApplicationScoped
public class InheritedProcessWiringSource implements DummyTaskWiringSource {

  @Override
  public Collection<BpmnTaskSpec> tasksOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return "InheritingWorkflowService".equals(bpmnProcessId)
        ? List.of(
            new BpmnTaskSpec("Activity_Own", "ownTask"),
            new BpmnTaskSpec("Activity_Inherited", "inheritedTask"))
        : List.of();

  }

}
