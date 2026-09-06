package io.vanillabp.integration.test.apptx;

import java.util.Collection;
import java.util.List;

import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Stands in for the BPMN model: 'TestProcess' has the two tasks this application handles.
 */
@ApplicationScoped
public class AppTxWiringSource implements DummyTaskWiringSource {

  @Override
  public Collection<BpmnTaskSpec> tasksOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return "TestProcess".equals(bpmnProcessId)
        ? List.of(
            new BpmnTaskSpec("Activity_Process", "processTask"),
            new BpmnTaskSpec("Activity_Fail", "failingTask"))
        : List.of();

  }

}
