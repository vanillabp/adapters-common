package io.vanillabp.integration.adapter.migration.workflowtask;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The refusal both platforms answer a <code>&#64;WorkflowService</code> with which does not
 * sit on the class serving the tasks: on an interface the class implements, or on an
 * annotation of the application's own carrying <code>&#64;WorkflowService</code>.
 * <p>
 * Neither of the two is what <code>&#64;Inherited</code> covers. A subclass of an annotated
 * class IS a workflow service and stays one, on both platforms: it is the class the handler
 * methods are read off, the class named when a BPMN process ID follows the class name, and the
 * class whose archive decides the workflow module. An implementing class is not, because Java
 * does not inherit method annotations from an interface, so the
 * <code>&#64;WorkflowTask</code> methods of interface and implementation are two different
 * sets and the platforms do not read the same one: Spring Boot reads the bean's class,
 * whose overriding methods carry no annotation, while Quarkus reads the annotated type of
 * its index, which is the interface, and serves the methods declared there on an instance
 * of the implementation. One source file with two behaviours is what the refusal avoids.
 * <p>
 * The text lives in the core because both platform integrations say it, each having found
 * the declaration its own way - among the bean definitions on Spring Boot, in the Jandex
 * index while a Quarkus application is built.
 */
public final class WorkflowServiceBelongsOnAClass {

  /**
   * What to do instead, the same for both shapes of the defect: the annotation goes where
   * the handler methods are, and a declaration meant for several classes goes onto their
   * common superclass.
   */
  private static final String REMEDY = """
      Move @WorkflowService onto the class holding the @WorkflowTask methods of this workflow \
      aggregate. Where several classes are meant to share one declaration, put it on a common \
      superclass: @WorkflowService is @Inherited, so every subclass of an annotated class is a \
      workflow service of its own, reading the declaration off the superclass. Name the \
      'bpmnProcess' on that superclass where the subclasses are meant to serve ONE process - \
      without it each subclass names its process after itself.""";

  private WorkflowServiceBelongsOnAClass() {
  }

  /**
   * @param interfaceName The interface carrying the annotation
   * @param implementingClasses The classes implementing it, empty where the platform cannot
   *     name them
   * @return The message ending the start
   */
  public static String foundOnAnInterface(
      final String interfaceName,
      final List<String> implementingClasses) {

    return """
        @WorkflowService sits on the interface
          %s%s
        An interface cannot be the workflow service of a workflow aggregate. VanillaBP reads the \
        @WorkflowTask methods off the annotated type, and Java does not inherit a method annotation \
        from an interface, so a handler of the implementing class is invisible through it. Quarkus \
        would serve the methods declared in the interface, Spring Boot would find no handler at \
        all: one source file, two behaviours.
        %s"""
        .formatted(
            interfaceName,
            broughtInBy("implemented by", implementingClasses),
            REMEDY);

  }

  /**
   * @param annotationName The annotation of the application carrying the annotation
   * @param annotatedClasses The classes using it, empty where the platform cannot name them
   * @return The message ending the start
   */
  public static String foundOnAnAnnotation(
      final String annotationName,
      final List<String> annotatedClasses) {

    return """
        @WorkflowService sits on the annotation
          @%s%s
        An annotation of your own carrying @WorkflowService does not declare a workflow service. \
        Spring Boot resolves such a meta-annotation and reads the class using it, while Quarkus \
        reads its index, which reports the annotation type itself as the annotated one and never \
        sees your class: one platform would find your handler methods, the other an annotation.
        %s"""
        .formatted(
            annotationName,
            broughtInBy("used on", annotatedClasses),
            REMEDY);

  }

  /**
   * The types which brought the declaration into the application, one per line, and nothing
   * where the platform could not determine them: an interface nobody implements is refused
   * as well, and there is then nothing to name.
   */
  private static String broughtInBy(
      final String relation,
      final List<String> typeNames) {

    if (typeNames.isEmpty()) {
      return "";
    }
    return "\n%s\n%s".formatted(
        relation,
        typeNames
            .stream()
            .map("  %s"::formatted)
            .collect(Collectors.joining("\n")));

  }

}
