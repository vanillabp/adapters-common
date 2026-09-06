package io.vanillabp.integration.test.samples.nobeansubclass;

import io.vanillabp.spi.service.WorkflowService;

/**
 * The declaration whose only subclass never became a CDI bean.
 */
@WorkflowService(workflowAggregateClass = NoBeanAggregate.class)
public abstract class DeclarationOfANoBeanSubclass {

}
