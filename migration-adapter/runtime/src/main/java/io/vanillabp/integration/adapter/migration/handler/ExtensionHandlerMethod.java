package io.vanillabp.integration.adapter.migration.handler;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

import io.vanillabp.integration.extension.spi.handler.HandlerContext;
import io.vanillabp.integration.extension.spi.handler.HandlerContract;
import io.vanillabp.integration.extension.spi.handler.HandlerValueSource;

/**
 * One method of a <code>&#64;WorkflowService</code> class an extension's contract
 * matched: the bean it belongs to, the keys it serves and the value sources its
 * parameters were bound to at startup.
 */
final class ExtensionHandlerMethod {

  private final HandlerContract contract;

  private final Class<?> workflowServiceClass;

  private final Method method;

  private final Supplier<Object> workflowServiceBean;

  private final List<HandlerValueSource> parameterBinders;

  /**
   * The keys this method serves. Empty means "the method's name", which is resolved
   * while scanning, so the list is never empty here.
   */
  private final List<String> lookupKeys;

  ExtensionHandlerMethod(
      final HandlerContract contract,
      final Class<?> workflowServiceClass,
      final Method method,
      final Supplier<Object> workflowServiceBean,
      final List<HandlerValueSource> parameterBinders,
      final List<String> lookupKeys) {

    this.contract = contract;
    this.workflowServiceClass = workflowServiceClass;
    this.method = method;
    this.workflowServiceBean = workflowServiceBean;
    this.parameterBinders = parameterBinders;
    this.lookupKeys = lookupKeys;

  }

  /**
   * @param candidates The keys the caller accepts
   * @return Whether this method serves any of them
   */
  boolean matches(
      final Collection<String> candidates) {

    if (lookupKeys.contains(HandlerContract.EVERY_KEY)) {
      return true;
    }
    return candidates
        .stream()
        .anyMatch(lookupKeys::contains);

  }

  /**
   * @param other Another method of the same contract and BPMN process
   * @return Whether both serve a common key - which makes them ambiguous
   */
  boolean overlaps(
      final ExtensionHandlerMethod other) {

    if (lookupKeys.contains(HandlerContract.EVERY_KEY) || other.lookupKeys.contains(HandlerContract.EVERY_KEY)) {
      return true;
    }
    return lookupKeys
        .stream()
        .anyMatch(other.lookupKeys::contains);

  }

  /**
   * @return The method, for guiding messages
   */
  String describe() {

    return "%s#%s".formatted(workflowServiceClass.getName(), method.getName());

  }

  /**
   * @return What this method is wired to, for guiding messages
   */
  String describeWiring() {

    return lookupKeys.contains(HandlerContract.EVERY_KEY)
        ? "every element of the BPMN process"
        : lookupKeys
            .stream()
            .map("'%s'"::formatted)
            .collect(java.util.stream.Collectors.joining(", "));

  }

  /**
   * Binds the parameters and invokes the method. Runtime exceptions of the method
   * propagate unchanged - only the extension knows whether its BPMS repeats the
   * notification behind them.
   *
   * @param context The values of this invocation
   * @return What the method returned, or <code>null</code> for a void one
   */
  Object invoke(
      final HandlerContext context) {

    final var arguments = parameterBinders
        .stream()
        .map(binder -> binder.valueFor(context))
        .toArray();
    try {
      return method.invoke(workflowServiceBean.get(), arguments);
    } catch (final IllegalAccessException e) {
      throw new IllegalStateException(
          "Could not invoke the @%s method '%s' of extension '%s'!"
              .formatted(contract.getAnnotationType().getSimpleName(), describe(), contract.getExtensionId()), e);
    } catch (final InvocationTargetException e) {
      if (e.getTargetException() instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      if (e.getTargetException() instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException(
          "The @%s method '%s' of extension '%s' threw a checked exception!"
              .formatted(contract.getAnnotationType().getSimpleName(), describe(),
                  contract.getExtensionId()), e.getTargetException());
    }

  }

}
