package io.vanillabp.integration.adapter.migration.handler;

import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import io.vanillabp.integration.adapter.migration.values.ValueConversion;
import io.vanillabp.integration.extension.spi.handler.CoreHandlerParameter;
import io.vanillabp.integration.extension.spi.handler.HandlerMultiInstance;
import io.vanillabp.integration.extension.spi.handler.HandlerValueSource;
import io.vanillabp.spi.service.MultiInstanceElement;
import io.vanillabp.spi.service.MultiInstanceElementResolver;
import io.vanillabp.spi.service.MultiInstanceIndex;
import io.vanillabp.spi.service.MultiInstanceTotal;
import io.vanillabp.spi.service.NoResolver;
import io.vanillabp.spi.service.TaskParam;

/**
 * The parameters every kind of handler method may take - the workflow aggregate,
 * <code>&#64;TaskParam</code> and the multi-instance context. Three scanners build their
 * methods with these: the one for <code>&#64;WorkflowTask</code>, the one for
 * <code>&#64;WorkflowStartedByBpms</code> and the generic one serving the contracts an
 * extension registers.
 * <p>
 * Which of them a method may take is the caller's decision ({@link CoreHandlerParameter}):
 * a workflow the BPMS started has no multi-instance context, and an extension says for
 * its own annotation what makes sense around its event. A parameter no allowed kind
 * serves is answered with <code>null</code> - the caller then either tries binders of its
 * own or writes the message naming what may stand there, which is where the annotation's
 * name belongs.
 */
public final class CoreParameterBinders {

  private CoreParameterBinders() {
  }

  /**
   * Binds one parameter with the core's own means.
   *
   * @param parameter The parameter
   * @param workflowAggregateClass The workflow-aggregate class of the method's workflow
   *          service
   * @param allowed The parameter kinds the caller permits
   * @param beanResolver Resolves beans by class, for
   *          <code>&#64;MultiInstanceElement(resolverBean = ...)</code>
   * @param location Names the parameter and its method, for guiding messages
   * @return How to produce the value, or <code>null</code> if no allowed kind serves the
   *         parameter
   */
  public static HandlerValueSource bind(
      final Parameter parameter,
      final Class<?> workflowAggregateClass,
      final Set<CoreHandlerParameter> allowed,
      final Function<Class<?>, Object> beanResolver,
      final String location) {

    if (allowed.contains(CoreHandlerParameter.TASK_PARAM)) {
      final var taskParam = parameter.getAnnotation(TaskParam.class);
      if (taskParam != null) {
        final var targetType = parameter.getType();
        return context -> ValueConversion.convert(
            context.getVariable(taskParam.value()),
            targetType,
            "@TaskParam(\"%s\") %s".formatted(taskParam.value(), location));
      }
    }

    if (allowed.contains(CoreHandlerParameter.MULTI_INSTANCE)) {
      final var multiInstanceIndex = parameter.getAnnotation(MultiInstanceIndex.class);
      if (multiInstanceIndex != null) {
        requireIntParameter(parameter, location, "@MultiInstanceIndex");
        return context -> requireMultiInstance(context.getMultiInstances(), multiInstanceIndex.value(), location)
            .index();
      }
      final var multiInstanceTotal = parameter.getAnnotation(MultiInstanceTotal.class);
      if (multiInstanceTotal != null) {
        requireIntParameter(parameter, location, "@MultiInstanceTotal");
        return context -> requireMultiInstance(context.getMultiInstances(), multiInstanceTotal.value(), location)
            .total();
      }
      final var multiInstanceElement = parameter.getAnnotation(MultiInstanceElement.class);
      if (multiInstanceElement != null) {
        return multiInstanceElementBinder(multiInstanceElement, location, beanResolver);
      }
    }

    // unannotated: the workflow aggregate
    if (allowed.contains(CoreHandlerParameter.WORKFLOW_AGGREGATE) && parameter.getType()
        .isAssignableFrom(workflowAggregateClass)) {
      return context -> context.getWorkflowAggregate();
    }

    return null;

  }

  private static HandlerValueSource multiInstanceElementBinder(
      final MultiInstanceElement annotation,
      final String location,
      final Function<Class<?>, Object> beanResolver) {

    final var resolverClass = annotation.resolverBean();
    final var hasResolver = !resolverClass.equals(NoResolver.class);
    final var hasName = !annotation.value().equals(MultiInstanceElement.USE_RESOLVER);
    if (hasResolver == hasName) {
      throw new IllegalStateException(
          """
              The %s has to set EITHER @MultiInstanceElement's value (the name of the \
              multi-instance element) OR its resolverBean!"""
              .formatted(location));
    }
    if (hasName) {
      return context -> requireMultiInstance(context.getMultiInstances(), annotation.value(), location).element();
    }
    return context -> {
      @SuppressWarnings("unchecked")
      final var resolver = (MultiInstanceElementResolver<Object, Object>) beanResolver.apply(resolverClass);
      if (resolver == null) {
        throw new IllegalStateException(
            """
                No bean of the resolver class '%s' (used by the %s) is available! Define it as a \
                bean of your application. If it IS one: on Quarkus the class has to be visible to \
                the build, which keeps resolver beans alive although nothing injects them - a \
                workflow module in its own Maven module needs a Jandex index for that (see the \
                jandex-maven-plugin)."""
                .formatted(resolverClass.getName(), location));
      }
      return resolver.resolve(context.getWorkflowAggregate(), asResolverView(context.getMultiInstances()));
    };

  }

  /**
   * Adapts the multi-instance values to the SPI's
   * {@link MultiInstanceElementResolver.MultiInstance} view, preserving the
   * outermost-first order.
   */
  private static Map<String, MultiInstanceElementResolver.MultiInstance<Object>> asResolverView(
      final Map<String, HandlerMultiInstance> multiInstances) {

    final var adapted = new LinkedHashMap<String, MultiInstanceElementResolver.MultiInstance<Object>>();
    multiInstances.forEach((
        name,
        value) -> adapted.put(name, new MultiInstanceElementResolver.MultiInstance<>() {

          @Override
          public Object getElement() {
            return value.element();
          }

          @Override
          public int getIndex() {
            return value.index();
          }

          @Override
          public int getTotal() {
            return value.total();
          }

        }));
    return adapted;

  }

  private static HandlerMultiInstance requireMultiInstance(
      final Map<String, HandlerMultiInstance> multiInstances,
      final String name,
      final String location) {

    final var multiInstance = multiInstances.get(name);
    if (multiInstance == null) {
      throw new IllegalStateException(
          """
              No multi-instance context named '%s' was supplied by the BPMS adapter for the %s! \
              Supplied multi-instance contexts: %s. Check the name against the BPMN's \
              multi-instance element."""
              .formatted(name, location, multiInstances.keySet()));
    }
    return multiInstance;

  }

  private static void requireIntParameter(
      final Parameter parameter,
      final String location,
      final String annotationName) {

    if (!parameter.getType().equals(int.class) && !parameter.getType().equals(Integer.class)) {
      throw new IllegalStateException(
          """
              The %s is annotated with %s but is not of type int/Integer! Change the parameter's \
              type."""
              .formatted(location, annotationName));
    }

  }

}
