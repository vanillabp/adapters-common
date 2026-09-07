package io.vanillabp.extension.sample;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a parameter of a {@link SampleNote} method which receives what happened to the
 * element - the shape of the Business Cockpit's <code>&#64;DetailsEvent</code>.
 * <p>
 * It is the second kind of parameter this extension contributes a binder for: the first
 * one is recognized by its TYPE ({@link SampleNoteDetails}), this one by its annotation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface SampleNoteEvent {
}
