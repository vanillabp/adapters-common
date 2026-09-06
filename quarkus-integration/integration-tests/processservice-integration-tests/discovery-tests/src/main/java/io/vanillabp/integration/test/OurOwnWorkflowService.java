package io.vanillabp.integration.test;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * An annotation of the application composing <code>&#64;WorkflowService</code>, the second
 * shape of the same defect: Jandex resolves no meta-annotation, so it reports THIS
 * annotation type as the annotated one and the class using it is never seen. See
 * {@code WorkflowServiceViaAnAnnotationOfItsOwnTest}.
 */
@Retention(RUNTIME)
@Target(TYPE)
@io.vanillabp.spi.service.WorkflowService(workflowAggregateClass = Aggregate.class)
public @interface OurOwnWorkflowService {

}
