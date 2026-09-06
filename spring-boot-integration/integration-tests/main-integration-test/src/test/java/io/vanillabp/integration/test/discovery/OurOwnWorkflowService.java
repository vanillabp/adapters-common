package io.vanillabp.integration.test.discovery;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * An annotation of the application composing {@code @WorkflowService}, the second shape
 * of the same defect: Spring Boot resolves such a meta-annotation, Quarkus reports the
 * annotation type itself as the annotated one. See
 * {@link WorkflowServiceDiscoveryTest#anAnnotationOfTheApplicationEndsTheStartNamingBothTypes}.
 */
@Retention(RUNTIME)
@Target(TYPE)
@WorkflowService(
    workflowAggregateClass = MetaAnnotatedAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "MetaAnnotatedProcess"))
public @interface OurOwnWorkflowService {

}
