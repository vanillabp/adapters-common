package io.vanillabp.integration.extension.spi.handler;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * What an extension checks about one occurrence of its own annotation while VanillaBP
 * scans the <code>&#64;WorkflowService</code> classes - the moment the method carrying
 * it is at hand.
 * <p>
 * An extension has rules the {@link HandlerContract} cannot express: an attribute which
 * only makes sense together with another one, a value which has to name something the
 * extension knows, an attribute a version of the extension does not serve yet. Checking
 * them here means the application does not boot with a wiring nobody would ever reach,
 * and it means the extension does not walk the classes of the application a second time
 * to find the method again. That second walk is what this hook replaces, and it never
 * saw the same methods anyway: the scan reads the PUBLIC methods of the class.
 * <p>
 * <b>Refuse by throwing.</b> The message is what the developer reads, so write what is
 * wrong and what to do about it; VanillaBP puts the annotation, the class, the method
 * and the extension in front of it and ends the boot.
 */
@FunctionalInterface
public interface HandlerAnnotationCheck {

  /**
   * Checks one occurrence of the extension's annotation.
   *
   * @param annotation The occurrence, of the contract's annotation type
   * @param method The method carrying it, of the <code>&#64;WorkflowService</code>
   *          class being scanned
   * @throws RuntimeException Naming what is wrong with it and what to do
   */
  void check(
      Annotation annotation,
      Method method);

}
