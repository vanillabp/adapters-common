package io.vanillabp.integration.extension.spi.handler;

/**
 * Produces the value of one handler-method parameter, once per invocation. A binder
 * returns it while the method is scanned, so everything which can be decided at
 * startup is decided there and the invocation only reads.
 */
@FunctionalInterface
public interface HandlerValueSource {

  /**
   * @param context The values of this invocation
   * @return The value to pass as the parameter
   */
  Object valueFor(
      HandlerContext context);

}
