package io.vanillabp.extension.sample;

import java.util.Optional;

/**
 * The service the sample extension offers per workflow aggregate, the way the Business
 * Cockpit offers its own: injected as
 * <code>SampleNoteService&lt;MyAggregate&gt;</code>, and injecting it is optional.
 *
 * @param <A> The workflow-aggregate type
 */
public interface SampleNoteService<A> {

  /**
   * The note the application's {@link SampleNote} method builds for a BPMN element.
   *
   * @param workflowAggregate The workflow aggregate of the workflow
   * @param elementId The BPMN element the note is about
   * @param kind What happened to it
   * @return The note, or empty where the application has no method for that element -
   *         the fallback is the extension's own business, and here it is "no note"
   */
  Optional<SampleNoteDetails> noteOf(
      A workflowAggregate,
      String elementId,
      SampleNoteDetails.Kind kind);

  /**
   * The same, for an event which is more than a read: what the method changed on the
   * workflow aggregate is saved.
   *
   * @param workflowAggregate The workflow aggregate of the workflow
   * @param elementId The BPMN element the note is about
   * @param kind What happened to it
   * @return The note, or empty where the application has no method for that element
   */
  Optional<SampleNoteDetails> recordNoteOf(
      A workflowAggregate,
      String elementId,
      SampleNoteDetails.Kind kind);

  /**
   * The BPMS holding the workflow of the given aggregate, asked through VanillaBP's
   * election - an extension addressing the first-priority adapter instead would talk to
   * the wrong BPMS for every workflow a migration has already moved.
   *
   * @param workflowAggregate The workflow aggregate
   * @return The id of the adapter holding the workflow
   */
  String bpmsHolding(
      A workflowAggregate);

  /**
   * What this extension is configured with for the workflow module of this aggregate:
   * <code>vanillabp.extensions.sample.greeting</code>, overridden by
   * <code>vanillabp.workflow-modules.&lt;id&gt;.extensions.sample.greeting</code>.
   *
   * @return The configured greeting, or <code>null</code>
   */
  String configuredGreeting();

}
