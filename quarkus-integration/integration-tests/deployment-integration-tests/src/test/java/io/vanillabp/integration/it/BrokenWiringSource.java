package io.vanillabp.integration.it;

import java.util.Collection;
import java.util.List;

import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Supplies a task the workflow service does not implement (see
 * {@link IncompleteTaskWiringTest}).
 */
@ApplicationScoped
public class BrokenWiringSource implements DummyTaskWiringSource {

  @Override
  public Collection<BpmnTaskSpec> tasksOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return "TaskProcess".equals(bpmnProcessId)
        ? List.of(new BpmnTaskSpec("Activity_Unknown", "notImplemented"))
        : List.of();

  }

}
