package io.vanillabp.integration.test.deployment;

import java.util.Collection;
import java.util.List;

import io.vanillabp.bpmsdouble.DummyBpmsInitiatedStartSource;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec;
import io.vanillabp.spi.service.BpmsStartTrigger;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Stands in for the BPMN model of the BPMS-initiated-start acceptance test:
 * 'StartProcess' has a timer and a signal start event, every other process of the
 * module has none.
 */
@ApplicationScoped
public class StartProcessStartEventSource implements DummyBpmsInitiatedStartSource {

  @Override
  public Collection<BpmsInitiatedStartSpec> startEventsOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return "StartProcess".equals(bpmnProcessId)
        ? List.of(
            BpmsInitiatedStartSpec.of("DailyTimer", BpmsStartTrigger.Kind.TIMER),
            new BpmsInitiatedStartSpec("SignalStart", BpmsStartTrigger.Kind.SIGNAL, "OrderReceived", null))
        : List.of();

  }

}
