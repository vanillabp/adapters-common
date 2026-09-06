package io.vanillabp.integration.test.activation;

import java.util.Collection;
import java.util.List;

import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Stands in for the BPMN model of the activation-identity test: the one task of
 * 'ActivationProcess' matching the handler of {@link ActivationWorkflowService}.
 */
@ApplicationScoped
public class ActivationProcessWiringSource implements DummyTaskWiringSource {

  @Override
  public Collection<BpmnTaskSpec> tasksOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return "ActivationProcess".equals(bpmnProcessId)
        ? List.of(new BpmnTaskSpec("Activity_RequestOffer", "requestOffer"))
        : List.of();

  }

}
