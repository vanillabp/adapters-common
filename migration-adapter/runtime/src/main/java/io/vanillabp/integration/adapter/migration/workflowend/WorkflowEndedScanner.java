package io.vanillabp.integration.adapter.migration.workflowend;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Supplier;

import io.vanillabp.integration.adapter.migration.handler.CoreParameterBinders;
import io.vanillabp.integration.adapter.migration.workflowtask.InheritedVersions;
import io.vanillabp.integration.adapter.migration.workflowtask.VersionRange;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext;
import io.vanillabp.integration.extension.spi.handler.CoreHandlerParameter;
import io.vanillabp.integration.extension.spi.handler.HandlerValueSource;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;

/**
 * Scans a <code>&#64;WorkflowService</code> class for
 * <code>&#64;WorkflowEnded</code> methods and builds their handlers. Runs once at
 * startup per workflow service class; defects yield guiding exceptions naming the
 * method and the fix.
 * <p>
 * The binding surface is the smallest of all handler kinds: a workflow which ended
 * has no task and no variables worth binding, so what a method may ask for is the
 * workflow aggregate and a {@link WorkflowEnd}.
 */
public final class WorkflowEndedScanner {

  /**
   * A workflow which ended has no task and no variables worth binding - the aggregate is
   * all the core has left to offer.
   */
  private static final java.util.Set<CoreHandlerParameter> CORE_PARAMETERS = java.util.Set
      .of(CoreHandlerParameter.WORKFLOW_AGGREGATE);

  /**
   * No parameter of such a method resolves a bean - the only kind which would is
   * <code>&#64;MultiInstanceElement</code>, which this handler does not allow.
   */
  private static final java.util.function.Function<Class<?>, Object> NO_BEAN_RESOLVER = beanClass -> null;

  private WorkflowEndedScanner() {
  }

  /**
   * @param workflowServiceClass The <code>&#64;WorkflowService</code> class
   * @param workflowAggregateClass The workflow-aggregate class of that service
   * @param workflowServiceBean Supplies the bean instance of the class
   * @param inherited What a method naming no <code>version</code> serves: the
   *          range of the <code>&#64;BpmnProcess</code> these handlers are registered
   *          for
   * @return The handlers, possibly empty
   */
  public static List<WorkflowEndedHandler> scan(
      final Class<?> workflowServiceClass,
      final Class<?> workflowAggregateClass,
      final Supplier<Object> workflowServiceBean,
      final InheritedVersions inherited) {

    final var handlers = new LinkedList<WorkflowEndedHandler>();
    for (final var method : workflowServiceClass.getMethods()) {
      final var annotation = method.getAnnotation(WorkflowEnded.class);
      if (annotation == null) {
        continue;
      }
      handlers
          .add(
              buildHandler(
                  workflowServiceClass,
                  method,
                  workflowAggregateClass,
                  workflowServiceBean,
                  annotation,
                  inherited));
    }
    return handlers;

  }

  private static WorkflowEndedHandler buildHandler(
      final Class<?> workflowServiceClass,
      final Method method,
      final Class<?> workflowAggregateClass,
      final Supplier<Object> workflowServiceBean,
      final WorkflowEnded annotation,
      final InheritedVersions inherited) {

    final var location = "%s#%s".formatted(workflowServiceClass.getName(), method.getName());
    // a public method of a package-private bean class is not accessible through
    // plain reflection - lift the check once at scan time
    method.trySetAccessible();

    if (!method.getReturnType().equals(void.class)) {
      throw new IllegalStateException(
          """
              The @WorkflowEnded method '%s' returns '%s'! A workflow which ended cannot be \
              influenced any more, so the method has nothing to return - declare it void."""
              .formatted(location, method.getReturnType().getName()));
    }

    final var binders = Arrays
        .stream(method.getParameters())
        .map(parameter -> buildParameterBinder(
            parameter,
            workflowAggregateClass,
            "parameter '%s' of @WorkflowEnded method '%s'".formatted(parameter.getName(), location)))
        .toList();

    final var versions = inherited
        .effectiveFor(Arrays
            .stream(annotation.version())
            .map(version -> VersionRange.parse(version, location))
            .toList());
    final var endEventId = annotation.id().equals(WorkflowEnded.ANY_END_EVENT)
        ? null
        : annotation.id();

    return new WorkflowEndedHandler(
        workflowServiceClass, method, workflowServiceBean, binders, endEventId, versions);

  }

  private static HandlerValueSource buildParameterBinder(
      final Parameter parameter,
      final Class<?> workflowAggregateClass,
      final String location) {

    // the one value only this kind of handler has; the rest comes from the binders all
    // handler kinds share
    if (parameter.getType().equals(WorkflowEnd.class)) {
      return context -> {
        final var end = context.payload(WorkflowEndedContext.class);
        return new WorkflowEnd(end.getKind(), end.getEndTime(), end.getEndEventId());
      };
    }

    final var binder = CoreParameterBinders
        .bind(parameter, workflowAggregateClass, CORE_PARAMETERS, NO_BEAN_RESOLVER, location);
    if (binder != null) {
      return binder;
    }

    throw new IllegalStateException(
        """
            The %s is neither of the workflow-aggregate type '%s' nor of type '%s'! A workflow which \
            ended offers nothing else: its tasks are gone and its variables are the aggregate you \
            already hold."""
            .formatted(location, workflowAggregateClass.getName(), WorkflowEnd.class.getName()));

  }

}
