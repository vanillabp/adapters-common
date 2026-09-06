package io.vanillabp.integration.test.deployment.conflict;

import java.util.Collection;
import java.util.List;

import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Stands in for the BPMN model of the version-conflict acceptance test: supplies the
 * tasks of 'ConflictProcess' matching the handlers of {@link ConflictWorkflowService}.
 */
@ApplicationScoped
public class ConflictProcessWiringSource implements DummyTaskWiringSource {

  @Override
  public Collection<BpmnTaskSpec> tasksOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return "ConflictProcess".equals(bpmnProcessId)
        ? List.of(
            new BpmnTaskSpec("Activity_Conflict", "conflictingTask"),
            new BpmnTaskSpec("Activity_Undisturbed", "undisturbedTask"))
        : List.of();

  }

}
