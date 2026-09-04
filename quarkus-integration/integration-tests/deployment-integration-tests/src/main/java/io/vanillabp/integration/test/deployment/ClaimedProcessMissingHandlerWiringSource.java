package io.vanillabp.integration.test.deployment;

import java.util.Collection;
import java.util.List;

import io.vanillabp.adapter.dummy.runtime.DummyTaskWiringSource;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The same two-process file as {@link CallingAndCalledWiringSource}, but the CLAIMED
 * process 'Calling' has a task no <code>&#64;WorkflowTask</code> method serves. Used by
 * {@code ClaimedProcessMissingHandlerTest}: that is the case which really is a defect,
 * and it still ends the boot.
 */
@ApplicationScoped
public class ClaimedProcessMissingHandlerWiringSource implements DummyTaskWiringSource {

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
      case "Calling" -> List.of(
          new BpmnTaskSpec("Activity_Juhu", "juhu"),
          new BpmnTaskSpec("Activity_Unknown", "notImplemented"));
      case "Called" -> List.of(new BpmnTaskSpec("Activity_Called", "doTheCalledWork"));
      default -> List.of();
    };

  }

}
