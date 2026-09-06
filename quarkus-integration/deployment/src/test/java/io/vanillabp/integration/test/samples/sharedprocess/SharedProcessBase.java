package io.vanillabp.integration.test.samples.sharedprocess;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * Names the BPMN process itself, so every subclass serves THAT process instead of one
 * named after itself. This is what several subclasses of one base need.
 */
@WorkflowService(
    workflowAggregateClass = SharedAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "SharedProcess"))
public abstract class SharedProcessBase {

}
