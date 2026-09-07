package io.vanillabp.extension.sample;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Holds the repetitions of {@link SampleNote} - one method may build the note of several
 * BPMN elements, the way one <code>&#64;WorkflowTask</code> method may serve several
 * tasks.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SampleNotes {

  /**
   * @return The repetitions
   */
  SampleNote[] value();

}
