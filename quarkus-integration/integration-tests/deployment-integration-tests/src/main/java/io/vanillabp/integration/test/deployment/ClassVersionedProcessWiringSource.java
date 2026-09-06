package io.vanillabp.integration.test.deployment;

import java.util.Collection;
import java.util.List;

import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Stands in for the BPMN model of the class-level version acceptance test: the one task
 * both generations of the model are wired to.
 */
@ApplicationScoped
public class ClassVersionedProcessWiringSource implements DummyTaskWiringSource {

  @Override
  public Collection<BpmnTaskSpec> tasksOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return "ClassVersionedProcess".equals(bpmnProcessId)
        ? List.of(new BpmnTaskSpec("Activity_Versioned", "versionedTask"))
        : List.of();

  }

}
