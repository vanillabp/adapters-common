package io.vanillabp.integration.adapter.migration.workflowstart;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Supplier;

import io.vanillabp.integration.adapter.migration.handler.CoreParameterBinders;
import io.vanillabp.integration.adapter.migration.workflowtask.InheritedVersions;
import io.vanillabp.integration.adapter.migration.workflowtask.VersionRange;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.extension.spi.handler.CoreHandlerParameter;
import io.vanillabp.integration.extension.spi.handler.HandlerValueSource;
import io.vanillabp.spi.service.BpmsStartTrigger;
import io.vanillabp.spi.service.WorkflowStartedByBpms;

/**
 * Scans a <code>&#64;WorkflowService</code> class for
 * <code>&#64;WorkflowStartedByBpms</code> methods and builds their handlers. Runs
 * once at startup per workflow service class; defects yield guiding exceptions
 * naming the method and the fix.
 * <p>
 * The binding surface is deliberately smaller than the one of
 * <code>&#64;WorkflowTask</code>: there is no task, so no task ID, no task event and
 * no multi-instance context. What a method may ask for is the workflow aggregate,
 * the {@link BpmsStartTrigger} and process variables via
 * <code>&#64;TaskParam</code>.
 */
public final class BpmsInitiatedStartScanner {

  /**
   * A workflow the BPMS started has no task around it, so no multi-instance scope
   * either - what such a method may take is the aggregate and the process variables the
   * model set.
   */
  private static final java.util.Set<CoreHandlerParameter> CORE_PARAMETERS = java.util.Set
      .of(CoreHandlerParameter.WORKFLOW_AGGREGATE, CoreHandlerParameter.TASK_PARAM);

  /**
   * No parameter of such a method resolves a bean - the only kind which would is
   * <code>&#64;MultiInstanceElement</code>, which this handler does not allow.
   */
  private static final java.util.function.Function<Class<?>, Object> NO_BEAN_RESOLVER = beanClass -> null;

  private BpmsInitiatedStartScanner() {
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
  public static List<BpmsInitiatedStartHandler> scan(
      final Class<?> workflowServiceClass,
      final Class<?> workflowAggregateClass,
      final Supplier<Object> workflowServiceBean,
      final InheritedVersions inherited) {

    final var handlers = new LinkedList<BpmsInitiatedStartHandler>();
    for (final var method : workflowServiceClass.getMethods()) {
      final var annotation = method.getAnnotation(WorkflowStartedByBpms.class);
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

  private static BpmsInitiatedStartHandler buildHandler(
      final Class<?> workflowServiceClass,
      final Method method,
      final Class<?> workflowAggregateClass,
      final Supplier<Object> workflowServiceBean,
      final WorkflowStartedByBpms annotation,
      final InheritedVersions inherited) {

    final var location = "%s#%s".formatted(workflowServiceClass.getName(), method.getName());
    // a public method of a package-private bean class is not accessible through
    // plain reflection - lift the check once at scan time
    method.trySetAccessible();

    final var returnsAggregate = validateReturnType(
        method,
        workflowAggregateClass,
        location);
    final var binders = Arrays
        .stream(method.getParameters())
        .map(parameter -> buildParameterBinder(
            parameter,
            workflowAggregateClass,
            "parameter '%s' of @WorkflowStartedByBpms method '%s'"
                .formatted(parameter.getName(), location)))
        .toList();
    if (!returnsAggregate && Arrays
        .stream(method.getParameters())
        .noneMatch(parameter -> parameter.getType().isAssignableFrom(workflowAggregateClass))) {
      throw new IllegalStateException(
          """
              The @WorkflowStartedByBpms method '%s' returns void but does not take the workflow \
              aggregate of class '%s' either! Take the aggregate VanillaBP built as a parameter to \
              enrich it, or return an aggregate you built yourself."""
              .formatted(location, workflowAggregateClass.getName()));
    }

    final var versions = inherited
        .effectiveFor(Arrays
            .stream(annotation.version())
            .map(version -> VersionRange.parse(version, location))
            .toList());
    final var startEventId = annotation.id().equals(WorkflowStartedByBpms.ANY_START_EVENT)
        ? null
        : annotation.id();

    return new BpmsInitiatedStartHandler(
        workflowServiceClass, method, workflowServiceBean, binders, startEventId, versions, returnsAggregate);

  }

  private static boolean validateReturnType(
      final Method method,
      final Class<?> workflowAggregateClass,
      final String location) {

    if (method.getReturnType().equals(void.class)) {
      return false;
    }
    if (method.getReturnType().isAssignableFrom(workflowAggregateClass)) {
      return true;
    }
    throw new IllegalStateException(
        """
            The @WorkflowStartedByBpms method '%s' returns '%s' which is not the workflow aggregate \
            of class '%s'! Return the aggregate you built, or declare the method void and modify \
            the aggregate VanillaBP passes in."""
            .formatted(location, method.getReturnType().getName(), workflowAggregateClass.getName()));

  }

  private static HandlerValueSource buildParameterBinder(
      final Parameter parameter,
      final Class<?> workflowAggregateClass,
      final String location) {

    // the one value only this kind of handler has; the rest comes from the binders all
    // three scanners share
    if (parameter.getType().equals(BpmsStartTrigger.class)) {
      return context -> {
        final var start = context.payload(BpmsInitiatedStartContext.class);
        return new BpmsStartTrigger(
            start.getKind(), start.getStartInstant(), start.getSignalName(), start.getStartEventId());
      };
    }

    final var binder = CoreParameterBinders
        .bind(parameter, workflowAggregateClass, CORE_PARAMETERS, NO_BEAN_RESOLVER, location);
    if (binder != null) {
      return binder;
    }

    throw new IllegalStateException(
        """
            The %s is neither annotated with @TaskParam nor of the workflow-aggregate type '%s' nor \
            of type '%s'! A method building the aggregate of a BPMS-initiated start may ask for the \
            aggregate, the trigger and process variables - nothing else exists at that moment."""
            .formatted(location, workflowAggregateClass.getName(), BpmsStartTrigger.class.getName()));

  }

}
