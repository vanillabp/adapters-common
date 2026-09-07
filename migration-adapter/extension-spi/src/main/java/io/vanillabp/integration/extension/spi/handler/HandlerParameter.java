package io.vanillabp.integration.extension.spi.handler;

import java.lang.annotation.Annotation;

/**
 * One parameter of a handler method, shown to the parameter binders an extension
 * contributes while the method is scanned. A binder decides from the type and the
 * annotations whether it serves the parameter.
 *
 * @see HandlerParameterBinder
 */
public interface HandlerParameter {

  /**
   * @return The declared type of the parameter
   */
  Class<?> getType();

  /**
   * @param <A> The annotation type
   * @param annotationType The annotation to look for
   * @return The annotation of the parameter, or <code>null</code>
   */
  <A extends Annotation> A getAnnotation(
      Class<A> annotationType);

  /**
   * @param annotationType The annotation to look for
   * @return Whether the parameter carries that annotation
   */
  boolean isAnnotationPresent(
      Class<? extends Annotation> annotationType);

  /**
   * Names the parameter, its method and its class - a binder rejecting a parameter
   * builds its guiding message from this, so the developer reads where to look.
   *
   * @return The description, e.g.
   *         <code>parameter 'details' of @UserTaskDetailsProvider method 'com.acme.Loan#approve'</code>
   */
  String describe();

}
