package io.vanillabp.integration.runtime.processservice;

import java.util.function.Function;

import io.quarkus.arc.Arc;
import io.quarkus.arc.SyntheticCreationalContext;
import io.quarkus.runtime.annotations.Recorder;
import io.vanillabp.integration.adapter.migration.processservice.ExtensionAggregateServiceContext;
import io.vanillabp.integration.extension.spi.election.WorkflowElection;
import io.vanillabp.integration.extension.spi.handler.ExtensionHandlers;
import io.vanillabp.integration.extension.spi.service.AggregateServiceFactory;

/**
 * Builds the per-aggregate service of an extension when the application first injects
 * it. The build step registered one synthetic bean per (service interface, workflow
 * aggregate) and this is what such a bean is created with.
 * <p>
 * Everything is looked up when the bean is built rather than injected: the extension's
 * factory and the process service of the aggregate both exist by then, and looking them
 * up here is what makes injecting the service OPTIONAL - an application which never asks
 * for it never runs any of this.
 */
@Recorder
public class ExtensionServiceRecorder {

  /**
   * @param serviceInterfaceName The service interface the extension offers
   * @param workflowAggregateClassName The workflow aggregate this bean serves
   * @return How to build the bean
   */
  public Function<SyntheticCreationalContext<Object>, Object> extensionService(
      final String serviceInterfaceName,
      final String workflowAggregateClassName) {

    return context -> {

      final var serviceInterface = loadClass(serviceInterfaceName);
      final var workflowAggregateClass = loadClass(workflowAggregateClassName);

      final var factory = Arc
          .container()
          .select(AggregateServiceFactory.class, jakarta.enterprise.inject.Any.Literal.INSTANCE)
          .stream()
          .filter(candidate -> serviceInterface.equals(candidate.getServiceInterface()))
          .findFirst()
          .orElseThrow(() -> new IllegalStateException(
              """
                  No AggregateServiceFactory of this application builds a '%s'! It was one while the \
                  application was built, so its bean disappeared afterwards - check the conditions on \
                  the extension's producer."""
                  .formatted(serviceInterfaceName)));

      final var processService = Arc
          .container()
          .select(ProcessServiceBaseCdiBean.class, jakarta.enterprise.inject.Any.Literal.INSTANCE)
          .stream()
          .filter(candidate -> workflowAggregateClass.equals(candidate.getWorkflowAggregateClass()))
          .findFirst()
          .orElseThrow(() -> new IllegalStateException(
              """
                  No ProcessService of the workflow aggregate '%s' exists, so the '%s' of an extension \
                  cannot be built either!"""
                  .formatted(workflowAggregateClassName, serviceInterfaceName)));

      @SuppressWarnings({
          "unchecked", "rawtypes"
      })
      final var serviceContext = new ExtensionAggregateServiceContext(
          processService.getMigrationProcessService(), Arc
              .container()
              .instance(ExtensionHandlers.class)
              .get(), Arc
                  .container()
                  .instance(WorkflowElection.class)
                  .get());

      return factory.createService(serviceContext);

    };

  }

  private static Class<?> loadClass(
      final String className) {

    try {
      return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
    } catch (final ClassNotFoundException e) {
      throw new IllegalStateException(
          "The class '%s' was on the classpath while the application was built and is not any more!"
              .formatted(className), e);
    }

  }

}
