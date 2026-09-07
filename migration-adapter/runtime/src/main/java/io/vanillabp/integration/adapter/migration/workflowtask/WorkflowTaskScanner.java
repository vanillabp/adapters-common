package io.vanillabp.integration.adapter.migration.workflowtask;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import io.vanillabp.integration.adapter.migration.handler.CoreParameterBinders;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.extension.spi.handler.CoreHandlerParameter;
import io.vanillabp.integration.extension.spi.handler.HandlerValueSource;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * Scans a <code>&#64;WorkflowService</code> class for
 * <code>&#64;WorkflowTask</code> annotated methods and builds
 * {@link WorkflowTaskHandler}s including the parameter binders (workflow aggregate,
 * <code>&#64;TaskId</code>, <code>&#64;TaskEvent</code>, <code>&#64;TaskParam</code>,
 * multi-instance annotations). Runs once at startup per workflow service class and
 * BPMN process - defects yield guiding exceptions naming the method and the fix.
 * <p>
 * A method naming no <code>version</code> serves the range of the
 * <code>&#64;BpmnProcess</code> its handlers are registered for (see
 * {@link InheritedVersions}).
 */
class WorkflowTaskScanner {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorkflowTaskScanner.class);

  /**
   * A workflow task is the handler with the widest binding surface: everything the core
   * knows how to bind may stand in its parameter list, plus the two parameters only a
   * task has.
   */
  private static final Set<CoreHandlerParameter> CORE_PARAMETERS = Set
      .of(
          CoreHandlerParameter.WORKFLOW_AGGREGATE,
          CoreHandlerParameter.TASK_PARAM,
          CoreHandlerParameter.MULTI_INSTANCE);

  private WorkflowTaskScanner() {
  }

  static List<WorkflowTaskHandler> scan(
      final Class<?> workflowServiceClass,
      final Class<?> workflowAggregateClass,
      final Supplier<Object> workflowServiceBean,
      final Function<Class<?>, Object> beanResolver,
      final List<TransactionAnnotationSpec> transactionAnnotations,
      final InheritedVersions inherited) {

    final var handlers = new LinkedList<WorkflowTaskHandler>();
    // transaction annotations of the application covering a handler break the
    // TaskException contract and cannot be worked around at runtime - all offending
    // methods of this class are reported in one exception below
    final var transactionDefects = new LinkedList<String>();
    final var transactionRemedies = new java.util.LinkedHashSet<String>();
    for (final var method : workflowServiceClass.getMethods()) {
      // @WorkflowTask is repeatable: one method may serve several tasks
      final var annotations = method.getAnnotationsByType(WorkflowTask.class);
      if (annotations.length == 0) {
        continue;
      }
      checkApplicationTransactions(
          workflowServiceClass,
          method,
          transactionAnnotations,
          transactionDefects,
          transactionRemedies);
      final var binders = buildParameterBinders(
          workflowServiceClass,
          method,
          workflowAggregateClass,
          beanResolver);
      final var asynchronousTask = Arrays
          .stream(method.getParameters())
          .anyMatch(parameter -> parameter.isAnnotationPresent(TaskId.class));
      // the events the method subscribes to: the union of its @TaskEvent
      // parameters' filters; without a @TaskEvent parameter only CREATED is
      // delivered (a CANCELED invocation would surprise handlers not asking
      // for lifecycle events)
      final var subscribedEvents = java.util.EnumSet.noneOf(TaskEvent.Event.class);
      Arrays
          .stream(method.getParameters())
          .map(parameter -> parameter.getAnnotation(TaskEvent.class))
          .filter(java.util.Objects::nonNull)
          .flatMap(taskEvent -> Arrays.stream(taskEvent.value()))
          .forEach(subscribedEvents::add);
      if (subscribedEvents.isEmpty()) {
        subscribedEvents.add(TaskEvent.Event.CREATED);
      }
      // The process variables the method reads - the core is the only place
      // these names exist, and an adapter whose BPMS ships a variable payload with
      // every delivery needs them to keep that payload at what is read
      final var taskParameters = Arrays
          .stream(method.getParameters())
          .map(parameter -> parameter.getAnnotation(TaskParam.class))
          .filter(java.util.Objects::nonNull)
          .map(TaskParam::value)
          .distinct()
          .sorted()
          .toList();
      for (final var annotation : annotations) {
        handlers.add(buildHandler(
            workflowServiceClass,
            method,
            workflowServiceBean,
            binders,
            annotation,
            asynchronousTask,
            subscribedEvents,
            taskParameters,
            inherited));
      }
    }
    if (!transactionDefects.isEmpty()) {
      throw new IllegalStateException(
          ApplicationTransactionCheck.buildFailureMessage(
              transactionDefects,
              transactionRemedies,
              workflowServiceClass));
    }
    return handlers;

  }

  private static void checkApplicationTransactions(
      final Class<?> workflowServiceClass,
      final Method method,
      final List<TransactionAnnotationSpec> transactionAnnotations,
      final List<String> defects,
      final Set<String> remedies) {

    final var findings = ApplicationTransactionCheck.inspect(
        workflowServiceClass,
        method,
        transactionAnnotations);
    if (findings.defect() != null) {
      defects.add(findings.defect());
      if (findings.remedy() != null) {
        remedies.add(findings.remedy());
      }
    }
    if (findings.notHonored() != null) {
      log.warn(
          """
              The @WorkflowTask method '{}#{}' carries a transaction annotation this platform does \
              NOT honor: {} So the transaction boundary it declares does not exist. On a workflow \
              task method you want none anyway, since VanillaBP runs the method in a transaction of \
              its own - but check the rest of your application for the same annotation, where it \
              silently does nothing as well.""",
          workflowServiceClass.getName(),
          method.getName(),
          findings.notHonored());
    }

  }

  private static WorkflowTaskHandler buildHandler(
      final Class<?> workflowServiceClass,
      final Method method,
      final Supplier<Object> workflowServiceBean,
      final List<HandlerValueSource> binders,
      final WorkflowTask annotation,
      final boolean asynchronousTask,
      final java.util.Set<TaskEvent.Event> subscribedEvents,
      final List<String> taskParameters,
      final InheritedVersions inherited) {

    final var location = "%s#%s".formatted(workflowServiceClass.getName(), method.getName());
    // a public method of a package-private bean class is not accessible through
    // plain reflection - lift the check once at scan time
    method.trySetAccessible();
    final var explicitTaskDefinition = !annotation.taskDefinition().equals(WorkflowTask.USE_METHOD_NAME);
    final var explicitActivityId = !annotation.id().equals(WorkflowTask.USE_METHOD_NAME);
    // neither attribute set: the method's name matches the task definition OR the
    // activity ID (convention); explicit attributes wire exactly what is given
    final String taskDefinition;
    final String activityId;
    if (!explicitTaskDefinition && !explicitActivityId) {
      taskDefinition = method.getName();
      activityId = method.getName();
    } else {
      taskDefinition = explicitTaskDefinition
          ? annotation.taskDefinition()
          : null;
      activityId = explicitActivityId
          ? annotation.id()
          : null;
    }
    final var versions = inherited
        .effectiveFor(Arrays
            .stream(annotation.version())
            .map(version -> VersionRange.parse(version, location))
            .toList());
    return new WorkflowTaskHandler(
        workflowServiceClass, method, workflowServiceBean, binders, taskDefinition, activityId, versions, asynchronousTask, subscribedEvents, taskParameters);

  }

  private static List<HandlerValueSource> buildParameterBinders(
      final Class<?> workflowServiceClass,
      final Method method,
      final Class<?> workflowAggregateClass,
      final Function<Class<?>, Object> beanResolver) {

    final var binders = new LinkedList<HandlerValueSource>();
    for (final var parameter : method.getParameters()) {
      binders.add(buildParameterBinder(
          workflowServiceClass,
          method,
          parameter,
          workflowAggregateClass,
          beanResolver));
    }
    return binders;

  }

  private static HandlerValueSource buildParameterBinder(
      final Class<?> workflowServiceClass,
      final Method method,
      final Parameter parameter,
      final Class<?> workflowAggregateClass,
      final Function<Class<?>, Object> beanResolver) {

    final var location = "parameter '%s' of @WorkflowTask method '%s#%s'"
        .formatted(parameter.getName(), workflowServiceClass.getName(), method.getName());

    // the two parameters only a task has - the rest is what every kind of handler
    // method may take, and comes from the binders all three scanners share
    if (parameter.isAnnotationPresent(TaskId.class)) {
      if (!parameter.getType().equals(String.class)) {
        throw new IllegalStateException(
            """
                The %s is annotated with @TaskId but is not of type String! The task's ID is \
                passed as a String - change the parameter's type."""
                .formatted(location));
      }
      return context -> context
          .payload(TaskInvocationContext.class)
          .getTaskId();
    }

    if (parameter.isAnnotationPresent(TaskEvent.class)) {
      if (!parameter.getType().equals(TaskEvent.Event.class)) {
        throw new IllegalStateException(
            """
                The %s is annotated with @TaskEvent but is not of type TaskEvent.Event! Change \
                the parameter's type."""
                .formatted(location));
      }
      return context -> context
          .payload(TaskInvocationContext.class)
          .getTaskEvent();
    }

    final var binder = CoreParameterBinders.bind(
        parameter,
        workflowAggregateClass,
        CORE_PARAMETERS,
        beanResolver,
        location);
    if (binder != null) {
      return binder;
    }
    throw new IllegalStateException(
        """
            The %s is neither annotated (@TaskId, @TaskEvent, @TaskParam, @MultiInstanceIndex, \
            @MultiInstanceTotal, @MultiInstanceElement) nor of the workflow-aggregate type '%s'! \
            Annotate the parameter or change its type to the workflow aggregate."""
            .formatted(location, workflowAggregateClass.getName()));

  }

}
