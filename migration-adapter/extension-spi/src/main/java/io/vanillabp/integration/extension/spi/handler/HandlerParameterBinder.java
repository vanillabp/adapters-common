package io.vanillabp.integration.extension.spi.handler;

import java.util.Optional;

/**
 * Binds the parameters VanillaBP knows nothing about. An extension contributes one
 * binder per parameter kind its own SPI adds - the Business Cockpit brings the
 * prefilled details object and its <code>&#64;DetailsEvent</code> parameter that way.
 * <p>
 * A binder is asked once per parameter while the handler method is scanned, and it
 * answers with the {@link HandlerValueSource} which produces the value per invocation.
 * Rejecting the parameter (an empty answer) hands it on to the next binder and finally
 * to the core binders the contract allows; a parameter nobody serves ends the boot with
 * a message naming the method, the parameter and what may stand there.
 * <p>
 * A binder which recognizes the parameter but finds it malformed - the right annotation
 * on the wrong type - throws instead of rejecting, so the developer reads what is wrong
 * rather than "unknown parameter".
 */
@FunctionalInterface
public interface HandlerParameterBinder {

  /**
   * @param parameter The parameter to bind
   * @return How to produce its value, or empty if this binder does not serve it
   */
  Optional<HandlerValueSource> bind(
      HandlerParameter parameter);

}
