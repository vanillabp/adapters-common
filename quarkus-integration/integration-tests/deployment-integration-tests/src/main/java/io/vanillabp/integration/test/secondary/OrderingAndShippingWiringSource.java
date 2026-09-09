package io.vanillabp.integration.test.secondary;

import java.util.Collection;
import java.util.List;

import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Stands in for a BPMN file holding the calling process and the called one, each with the
 * task its workflow service has the handler of.
 */
@ApplicationScoped
public class OrderingAndShippingWiringSource implements DummyTaskWiringSource {

  @Override
  public List<String> executableProcessesOf(
      final String adapterId,
      final String workflowModuleId,
      final String filename) {

    return List.of("Ordering", "Shipping");

  }

  @Override
  public Collection<BpmnTaskSpec> tasksOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return switch (bpmnProcessId) {
      case "Ordering" -> List.of(new BpmnTaskSpec("Activity_Order", "orderTask"));
      case "Shipping" -> List.of(new BpmnTaskSpec("Activity_AwaitShipment", "awaitShipment"));
      default -> List.of();
    };

  }

}
