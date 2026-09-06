package io.vanillabp.integration.test.samples.annotatedbeanbase;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import jakarta.inject.Singleton;

/**
 * A declaration on a class which is a bean itself, so it serves the process next to its
 * subclass rather than only declaring it.
 */
@Singleton
@WorkflowService(
    workflowAggregateClass = BeanBaseAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "BaseProcess"))
public class AnnotatedBaseWhichIsABean {

}
