package io.vanillabp.integration.test.deployment;

import java.util.Collection;
import java.util.List;

import io.vanillabp.adapter.dummy.runtime.DummyTaskWiringSource;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Stands in for a BPMN file holding TWO executable processes: 'Calling', which
 * {@link CallingWorkflowService} serves, and 'Called', which nobody serves. The task of
 * 'Called' is a service task - the non-optional kind, which is what used to end the
 * boot.
 */
@ApplicationScoped
public class CallingAndCalledWiringSource implements DummyTaskWiringSource {

  @Override
  public List<String> executableProcessesOf(
      final String adapterId,
      final String workflowModuleId,
      final String filename) {

    return List.of("Calling", "Called");

  }

  @Override
  public Collection<BpmnTaskSpec> tasksOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return switch (bpmnProcessId) {
      case "Calling" -> List.of(new BpmnTaskSpec("Activity_Juhu", "juhu"));
      case "Called" -> List.of(new BpmnTaskSpec("Activity_Called", "doTheCalledWork"));
      default -> List.of();
    };

  }

}
