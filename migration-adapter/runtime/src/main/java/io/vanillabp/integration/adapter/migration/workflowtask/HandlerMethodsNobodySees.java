package io.vanillabp.integration.adapter.migration.workflowtask;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowStartedByBpms;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * Two ways of writing a handler method which the developer cannot tell apart from a
 * working one, and which the scanners of this package never get to see, because both
 * scanners read the PUBLIC methods of the workflow service class:
 * <ul>
 * <li>a handler method which is not public - the scan lists public methods only, so a
 * protected, package private or private one is not there at all;</li>
 * <li>an override which repeats none of the annotations - Java never inherits a method
 * annotation, so the overriding method replaces the annotated one with a method carrying
 * nothing.</li>
 * </ul>
 * Both end in a task nobody serves, and the message about that task says the method is
 * missing, which is the one thing the developer can see is not true. So the startup names
 * the method, the class it is declared in and the way out, and it does that from the core,
 * because a class reaches the scanners the same way on both platforms.
 * <p>
 * What this deliberately does not do is serve those methods anyway. Reflection could lift
 * the visibility of a handler, and from the moment VanillaBP does it, which methods a
 * class offers stops being the decision of whoever wrote the class.
 */
public final class HandlerMethodsNobodySees {

  /**
   * The annotations which turn a method into a handler. All three are read off the class
   * the same way, so all three are lost the same way.
   */
  private static final List<Class<? extends Annotation>> HANDLER_ANNOTATIONS = List.of(
      WorkflowTask.class,
      WorkflowStartedByBpms.class,
      WorkflowEnded.class);

  private HandlerMethodsNobodySees() {
  }

  /**
   * @param workflowServiceClass The class the platform integration hands to the scanners
   * @return The message naming every handler method of the class and of its superclasses
   *     which the scan cannot reach, or <code>null</code> where there is none
   */
  public static String reportFor(
      final Class<?> workflowServiceClass) {

    final var findings = new LinkedList<String>();
    declarationsBySignature(workflowServiceClass)
        .values()
        .forEach(declarations -> inspect(declarations, findings));
    if (findings.isEmpty()) {
      return null;
    }
    return """
        Handler methods of the workflow service class '%s' which VanillaBP does not see:
        %s"""
        .formatted(
            workflowServiceClass.getName(),
            findings
                .stream()
                .map("  - %s"::formatted)
                .collect(Collectors.joining("\n")));

  }

  /**
   * Every method declared by the class or by one of its superclasses, grouped by the
   * signature which decides whether one overrides another, most derived declaration first.
   * The methods the compiler added itself are left out: a bridge method of a covariant
   * override carries the annotations of neither declaration and would look like a handler
   * lost on the way.
   */
  private static LinkedHashMap<String, List<Method>> declarationsBySignature(
      final Class<?> workflowServiceClass) {

    final var bySignature = new LinkedHashMap<String, List<Method>>();
    for (var type = workflowServiceClass; (type != null) && (type != Object.class); type = type.getSuperclass()) {
      for (final var method : type.getDeclaredMethods()) {
        if (method.isSynthetic() || method.isBridge()) {
          continue;
        }
        bySignature
            .computeIfAbsent(signatureOf(method), signature -> new LinkedList<>())
            .add(method);
      }
    }
    return bySignature;

  }

  /**
   * The declarations of one signature, most derived first: what the scan reaches is the
   * first of them, and whether that one carries an annotation decides which of the two
   * defects this is.
   */
  private static void inspect(
      final List<Method> declarations,
      final List<String> findings) {

    final var reachedByTheScan = declarations.getFirst();
    if (isHandler(reachedByTheScan)) {
      if (!Modifier.isPublic(reachedByTheScan.getModifiers())) {
        findings.add(notPublic(reachedByTheScan));
      }
      return;
    }
    for (final var inherited : declarations.subList(1, declarations.size())) {
      if (!isHandler(inherited)) {
        continue;
      }
      // a private method is not overridden by anything, so the method of the same name
      // further down is a method of its own and the annotated one is invisible for the
      // reason the other finding names
      if (Modifier.isPrivate(inherited.getModifiers())) {
        findings.add(notPublic(inherited));
      } else {
        findings.add(annotationDroppedByTheOverride(reachedByTheScan, inherited));
      }
      return;
    }

  }

  private static boolean isHandler(
      final Method method) {

    return HANDLER_ANNOTATIONS
        .stream()
        .anyMatch(method::isAnnotationPresent);

  }

  private static String notPublic(
      final Method method) {

    return """
        the %s method '%s#%s' is %s, and VanillaBP reads the PUBLIC methods of a workflow \
        service class, so this one is not among the methods it can wire and the task it is \
        meant to serve stays unserved. Make the method public. VanillaBP does not lift the \
        visibility itself: which methods a class offers is the decision of whoever wrote it."""
        .formatted(
            handlerAnnotationsOf(method),
            method
                .getDeclaringClass()
                .getName(),
            method.getName(),
            visibilityOf(method));

  }

  private static String annotationDroppedByTheOverride(
      final Method override,
      final Method inherited) {

    return """
        the method '%s#%s' overrides '%s#%s', which is a %s method, and carries no annotation \
        of its own. Java never inherits a method annotation, so the override replaced the \
        handler with a method VanillaBP knows nothing about and the task it served stays \
        unserved. Repeat the annotation on the override, or give the override a name of its \
        own so that it does not replace the handler."""
        .formatted(
            override
                .getDeclaringClass()
                .getName(),
            override.getName(),
            inherited
                .getDeclaringClass()
                .getName(),
            inherited.getName(),
            handlerAnnotationsOf(inherited));

  }

  /**
   * The handler annotations of a method, written the way they are written in the source, so
   * the reader can search their own class for the text of the message.
   */
  private static String handlerAnnotationsOf(
      final Method method) {

    return HANDLER_ANNOTATIONS
        .stream()
        .filter(method::isAnnotationPresent)
        .map(annotation -> "@%s".formatted(annotation.getSimpleName()))
        .collect(Collectors.joining(" and "));

  }

  private static String visibilityOf(
      final Method method) {

    final var modifiers = method.getModifiers();
    if (Modifier.isPrivate(modifiers)) {
      return "private";
    }
    if (Modifier.isProtected(modifiers)) {
      return "protected";
    }
    return "package private";

  }

  private static String signatureOf(
      final Method method) {

    return "%s(%s)".formatted(
        method.getName(),
        Arrays
            .stream(method.getParameterTypes())
            .map(Class::getName)
            .collect(Collectors.joining(",")));

  }

}
