package io.vanillabp.integration.extension.spi.handler;

/**
 * One multi-instance scope a handler invocation runs in, keyed by the BPMN element
 * carrying the multi-instance characteristics.
 *
 * @param element The current element of the collection iterated over
 * @param index The zero-based index of the current iteration
 * @param total The number of iterations of that element
 */
public record HandlerMultiInstance(
                                   Object element,
                                   int index,
                                   int total) {
}
