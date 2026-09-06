package io.vanillabp.integration.test.delivery;

import java.util.Collection;
import java.util.List;

import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Stands in for the BPMN model of the inbound-idempotency test: the tasks of
 * 'DeliveryProcess' matching the handlers of {@link DeliveryWorkflowService}.
 */
@ApplicationScoped
public class DeliveryProcessWiringSource implements DummyTaskWiringSource {

  @Override
  public Collection<BpmnTaskSpec> tasksOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return "DeliveryProcess".equals(bpmnProcessId)
        ? List.of(
            new BpmnTaskSpec("Activity_Process", "processTask"),
            new BpmnTaskSpec("Activity_Error", "raiseBpmnError"),
            new BpmnTaskSpec("Activity_Fail", "failTask"),
            new BpmnTaskSpec("Activity_Undeduplicated", "undeduplicatedTask"),
            new BpmnTaskSpec("Activity_Await", "awaitCompletion"),
            new BpmnTaskSpec("Activity_Concurrent", "concurrentTask"))
        : List.of();

  }

}
