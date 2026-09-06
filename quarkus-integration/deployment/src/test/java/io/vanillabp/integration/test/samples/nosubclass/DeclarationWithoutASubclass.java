package io.vanillabp.integration.test.samples.nosubclass;

import io.vanillabp.spi.service.WorkflowService;

/**
 * An abstract declaration nobody extends, so no class of the application serves it.
 */
@WorkflowService(workflowAggregateClass = LonelyAggregate.class)
public abstract class DeclarationWithoutASubclass {

}
