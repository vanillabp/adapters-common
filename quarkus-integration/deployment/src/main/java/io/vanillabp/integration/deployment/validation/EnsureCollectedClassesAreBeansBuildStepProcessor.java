package io.vanillabp.integration.deployment.validation;

import java.util.List;
import java.util.stream.Collectors;

import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem.ValidationErrorBuildItem;
import io.quarkus.arc.processor.BeanInfo;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;

/**
 * Validates that classes collected by other build steps (e.g. workflow services or
 * aggregate persistence implementations) are actual CDI beans at runtime.
 */
public class EnsureCollectedClassesAreBeansBuildStepProcessor {

  /**
   * The classes collected are not necessarily injected by application code but looked up
   * dynamically at runtime. This build step prevents ArC from removing them as unused
   * beans (which would also cause false positives in
   * {@link #ensureCollectedClassesAreBeans(ValidationPhaseBuildItem, List, BuildProducer)}
   * since removed beans are not part of the validation context any more).
   *
   * @param classIsBeanValidationBuildItems The classes collected by other build steps
   * @param unremovableBeans Producer for unremovable-bean build items
   */
  @BuildStep
  void preserveCollectedBeans(
      final List<EnsureClassIsBeanValidationBuildItem> classIsBeanValidationBuildItems,
      final BuildProducer<UnremovableBeanBuildItem> unremovableBeans) {

    if (classIsBeanValidationBuildItems.isEmpty()) {
      return;
    }
    unremovableBeans.produce(UnremovableBeanBuildItem
        .beanTypes(classIsBeanValidationBuildItems
            .stream()
            .map(EnsureClassIsBeanValidationBuildItem::getClassName)
            .collect(Collectors.toSet())));

  }

  /**
   * Checks each collected class during ArC's validation phase, the documented hook for
   * custom bean validations (in contrast to the bean-registration phase, synthetic beans
   * are visible here as well). A class passes the check if any bean's set of bean types
   * contains the class. ArC's computed bean types are used on purpose: they respect
   * restrictions like {@link jakarta.enterprise.inject.Typed}.
   * <p>
   * A class which VanillaBP registers by its own name asks for more than that: there the
   * bean has to BE that class, because a bean of a subclass is a different workflow
   * service serving a BPMN process of its own.
   *
   * @param validationPhase The ArC validation phase
   * @param classIsBeanValidationBuildItems The classes collected by other build steps
   * @param validationErrors Producer for validation errors failing the build
   */
  @BuildStep
  void ensureCollectedClassesAreBeans(
      final ValidationPhaseBuildItem validationPhase,
      final List<EnsureClassIsBeanValidationBuildItem> classIsBeanValidationBuildItems,
      final BuildProducer<ValidationErrorBuildItem> validationErrors) {

    final var beans = validationPhase
        .getContext()
        .beans()
        .stream()
        .toList();

    classIsBeanValidationBuildItems
        .forEach(buildItem -> {
          final var requiredType = buildItem.getClassName();
          final var found = beans
              .stream()
              .anyMatch(bean -> buildItem.isABeanOfASubclassCounts()
                  ? bean
                      .getTypes()
                      .stream()
                      .anyMatch(beanType -> beanType.name().equals(requiredType))
                  : isBeanOfExactly(bean, requiredType));
          if (!found) {
            validationErrors.produce(new ValidationErrorBuildItem(
                new IllegalStateException(
                    """
                        Class
                          %s
                        was found by the VanillaBP extension as a
                          %s
                        but %s.
                        %s"""
                        .formatted(
                            buildItem.getClassName(),
                            buildItem.getUsageDescription(),
                            buildItem.isABeanOfASubclassCounts()
                                ? "neither the class itself nor any implementation is a CDI bean"
                                : "the class itself is not a CDI bean",
                            buildItem.getRemedy() == null
                                ? "Please annotate it with a bean-defining annotation such as @ApplicationScoped."
                                : buildItem.getRemedy()))));
          }
        });

  }

  /**
   * Whether a bean stands for exactly the class asked about: the class ArC builds the bean
   * from, which is the class itself for a class bean and the returned type for a producer.
   */
  private static boolean isBeanOfExactly(
      final BeanInfo bean,
      final org.jboss.jandex.DotName requiredType) {

    final var implementation = bean.getImplClazz();
    return (implementation != null) && implementation.name().equals(requiredType);

  }

}
