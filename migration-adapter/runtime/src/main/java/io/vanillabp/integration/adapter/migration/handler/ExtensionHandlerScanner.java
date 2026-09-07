package io.vanillabp.integration.adapter.migration.handler;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import io.vanillabp.integration.extension.spi.handler.HandlerContract;
import io.vanillabp.integration.extension.spi.handler.HandlerValueSource;

/**
 * Scans a <code>&#64;WorkflowService</code> class for the methods of one extension
 * contract, the way {@code WorkflowTaskScanner} does it for
 * <code>&#64;WorkflowTask</code>: find the annotated methods, read the keys they serve,
 * bind their parameters - the extension's own binders first, then the core's - and check
 * the return type against what the contract promised.
 * <p>
 * Everything decidable at startup is decided here, so an invocation only reads. Defects
 * end the boot with a message naming the extension, the method and the fix.
 */
final class ExtensionHandlerScanner {

  private ExtensionHandlerScanner() {
  }

  /**
   * @param contract The contract to scan for
   * @param workflowServiceClass The <code>&#64;WorkflowService</code> class
   * @param workflowAggregateClass Its workflow-aggregate class
   * @param workflowServiceBean Supplies the bean instance of that class
   * @param beanResolver Resolves beans by class, for multi-instance element resolvers
   * @return The methods found, possibly none
   */
  static List<ExtensionHandlerMethod> scan(
      final HandlerContract contract,
      final Class<?> workflowServiceClass,
      final Class<?> workflowAggregateClass,
      final Supplier<Object> workflowServiceBean,
      final Function<Class<?>, Object> beanResolver) {

    final var methods = new LinkedList<ExtensionHandlerMethod>();
    for (final var method : workflowServiceClass.getMethods()) {
      final var annotations = method.getAnnotationsByType(contract.getAnnotationType());
      if (annotations.length == 0) {
        continue;
      }
      methods
          .add(
              build(
                  contract,
                  workflowServiceClass,
                  workflowAggregateClass,
                  workflowServiceBean,
                  beanResolver,
                  method,
                  annotations));
    }
    return methods;

  }

  private static ExtensionHandlerMethod build(
      final HandlerContract contract,
      final Class<?> workflowServiceClass,
      final Class<?> workflowAggregateClass,
      final Supplier<Object> workflowServiceBean,
      final Function<Class<?>, Object> beanResolver,
      final Method method,
      final java.lang.annotation.Annotation[] annotations) {

    final var annotationName = "@"
        + contract.getAnnotationType().getSimpleName();
    final var where = "%s method '%s#%s' of extension '%s'"
        .formatted(annotationName, workflowServiceClass.getName(), method.getName(), contract.getExtensionId());

    // a public method of a package-private bean class is not accessible through plain
    // reflection - lift the check once at scan time
    method.trySetAccessible();

    if (!contract.deliversReturnValue() && !method.getReturnType().equals(void.class)) {
      throw new IllegalStateException(
          """
              The %s returns '%s', but this extension does not deliver what its methods return! \
              Declare the method void - nobody would ever read the value."""
              .formatted(where, method.getReturnType().getName()));
    }

    final var lookupKeys = new LinkedHashSet<String>();
    for (final var annotation : annotations) {
      final var keys = contract
          .getLookupKeys()
          .apply(annotation);
      if (keys != null) {
        keys
            .stream()
            .filter(key -> (key != null) && !key.isBlank())
            .forEach(lookupKeys::add);
      }
    }
    if (lookupKeys.isEmpty()) {
      // the convention every VanillaBP annotation follows: an attribute naming nothing
      // means the method's own name is the key
      lookupKeys.add(method.getName());
    }

    final var binders = Arrays
        .stream(method.getParameters())
        .map(parameter -> bind(
            contract,
            workflowAggregateClass,
            beanResolver,
            parameter,
            "parameter '%s' of %s".formatted(parameter.getName(), where)))
        .toList();

    return new ExtensionHandlerMethod(
        contract, workflowServiceClass, method, workflowServiceBean, binders, List.copyOf(lookupKeys));

  }

  private static HandlerValueSource bind(
      final HandlerContract contract,
      final Class<?> workflowAggregateClass,
      final Function<Class<?>, Object> beanResolver,
      final java.lang.reflect.Parameter parameter,
      final String location) {

    final var view = new ReflectiveHandlerParameter(parameter, location);
    for (final var binder : contract.getParameterBinders()) {
      final var bound = binder.bind(view);
      if ((bound != null) && bound.isPresent()) {
        return bound.get();
      }
    }

    final var core = CoreParameterBinders
        .bind(parameter, workflowAggregateClass, contract.getCoreParameters(), beanResolver, location);
    if (core != null) {
      return core;
    }

    throw new IllegalStateException(
        """
            Nothing can bind the %s! Neither a parameter binder of extension '%s' serves it, nor is \
            it one of the parameters VanillaBP binds for this extension (%s). Change its type, \
            annotate it, or let the extension contribute a binder for it."""
            .formatted(
                location,
                contract.getExtensionId(),
                describeCoreParameters(contract, workflowAggregateClass)));

  }

  private static String describeCoreParameters(
      final HandlerContract contract,
      final Class<?> workflowAggregateClass) {

    if (contract.getCoreParameters().isEmpty()) {
      return "this extension allows none";
    }
    return contract
        .getCoreParameters()
        .stream()
        .map(parameter -> switch (parameter) {
          case WORKFLOW_AGGREGATE -> "the workflow aggregate '%s'".formatted(workflowAggregateClass.getName());
          case TASK_PARAM -> "@TaskParam";
          case MULTI_INSTANCE -> "@MultiInstanceElement/@MultiInstanceIndex/@MultiInstanceTotal";
        })
        .collect(java.util.stream.Collectors.joining(", "));

  }

}
