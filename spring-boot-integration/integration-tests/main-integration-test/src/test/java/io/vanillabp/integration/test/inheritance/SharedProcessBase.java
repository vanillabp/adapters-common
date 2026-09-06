package io.vanillabp.integration.test.inheritance;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * Names the BPMN process itself, which is what several subclasses of one base need: without
 * it each of them would name its process after itself, and one workflow aggregate has one
 * process to start.
 */
@WorkflowService(
    workflowAggregateClass = SharedProcessAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "SharedProcess"))
public abstract class SharedProcessBase {

}
