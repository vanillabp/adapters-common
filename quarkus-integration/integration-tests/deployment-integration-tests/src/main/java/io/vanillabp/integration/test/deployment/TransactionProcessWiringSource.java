package io.vanillabp.integration.test.deployment;

import java.util.Collection;
import java.util.List;

import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Stands in for the BPMN model of the transaction-contract acceptance test: supplies the
 * tasks of 'TransactionProcess' matching the handlers of
 * {@link TransactionWorkflowService}; other processes have no tasks.
 */
@ApplicationScoped
public class TransactionProcessWiringSource implements DummyTaskWiringSource {

  @Override
  public Collection<BpmnTaskSpec> tasksOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return "TransactionProcess".equals(bpmnProcessId)
        ? List.of(
            new BpmnTaskSpec("Activity_Nested", "nestedTaskException"),
            new BpmnTaskSpec("Activity_Swallowed", "swallowedNestedFailure"),
            new BpmnTaskSpec("Activity_Accepted", "acceptedAnnotation"))
        : List.of();

  }

}
