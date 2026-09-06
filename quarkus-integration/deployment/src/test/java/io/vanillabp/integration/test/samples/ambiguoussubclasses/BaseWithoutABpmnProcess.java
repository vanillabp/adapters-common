package io.vanillabp.integration.test.samples.ambiguoussubclasses;

import io.vanillabp.spi.service.WorkflowService;

/**
 * Names no BPMN process, so every subclass names its process after itself - which is one
 * process too many as soon as there are two subclasses.
 */
@WorkflowService(workflowAggregateClass = AmbiguousAggregate.class)
public abstract class BaseWithoutABpmnProcess {

}
