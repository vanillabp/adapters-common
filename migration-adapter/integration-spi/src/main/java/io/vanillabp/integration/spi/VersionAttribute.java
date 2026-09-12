package io.vanillabp.integration.spi;

import java.lang.reflect.AnnotatedElement;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * Whether a workflow aggregate carries the attribute a persistence layer increments per
 * write, which is what turns a second writer from a silent overwrite into an exception.
 * The implementation behind the default of
 * {@link AggregatePersistenceAware#detectsConcurrentModification()}.
 * <p>
 * One question, asked once, for every persistence layer VanillaBP supports. JPA calls the
 * annotation <code>Version</code>, Spring Data calls it <code>Version</code>, and Panache
 * on Quarkus does too, so the SIMPLE name is what decides. Writing the question per
 * technology would be the same question several times, and the core must not depend on any
 * of those APIs to ask it.
 * <p>
 * A persistence layer following the same convention under another name is not recognised,
 * and neither is a store which notices a second writer some other way. Both override
 * {@link AggregatePersistenceAware#detectsConcurrentModification()} and answer for
 * themselves; the cost of not doing so is a warning given where none was needed.
 */
public final class VersionAttribute {

  /**
   * The simple name every persistence layer VanillaBP supports gives that annotation.
   */
  private static final String VERSION_ANNOTATION = "Version";

  private VersionAttribute() {
    // utility class
  }

  /**
   * Whether the given class or one of its super classes declares a version attribute,
   * looking at fields as well as at methods (JPA property access).
   *
   * @param workflowAggregateClass The workflow aggregate's class
   * @return Whether a version attribute was found
   */
  public static boolean isDeclaredBy(
      final Class<?> workflowAggregateClass) {

    var type = workflowAggregateClass;
    while ((type != null) && (type != Object.class)) {
      final var annotated = Stream
          .concat(
              Arrays.stream(type.getDeclaredFields()),
              Arrays.stream(type.getDeclaredMethods()))
          .map(AnnotatedElement.class::cast)
          .toList();
      if (annotated
          .stream()
          .anyMatch(VersionAttribute::isVersionAnnotated)) {
        return true;
      }
      type = type.getSuperclass();
    }
    return false;

  }

  private static boolean isVersionAnnotated(
      final AnnotatedElement element) {

    return Arrays
        .stream(element.getAnnotations())
        .map(annotation -> annotation
            .annotationType()
            .getSimpleName())
        .anyMatch(VERSION_ANNOTATION::equals);

  }

}
