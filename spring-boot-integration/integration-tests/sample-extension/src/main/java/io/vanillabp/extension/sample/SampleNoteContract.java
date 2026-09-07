package io.vanillabp.extension.sample;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import io.vanillabp.integration.extension.spi.handler.CoreHandlerParameter;
import io.vanillabp.integration.extension.spi.handler.HandlerContract;
import io.vanillabp.integration.extension.spi.handler.HandlerParameter;
import io.vanillabp.integration.extension.spi.handler.HandlerValueSource;

/**
 * What the sample extension tells VanillaBP about {@link SampleNote}: how a method is
 * matched, what may stand in its parameter list and that what it returns is handed back.
 * <p>
 * This is the whole wiring an extension writes. Everything else - finding the methods,
 * loading the aggregate, the transaction, the guiding messages - is what VanillaBP does
 * for its own annotations and now does for this one too.
 */
public final class SampleNoteContract {

  /**
   * The extension's id, named in every message about one of its methods and the section
   * its configuration lives in (<code>vanillabp.extensions.sample.*</code>).
   */
  public static final String EXTENSION_ID = "sample";

  private SampleNoteContract() {
  }

  /**
   * @return The contract to register with {@code ExtensionHandlers}
   */
  public static HandlerContract build() {

    return HandlerContract
        .of(EXTENSION_ID, SampleNote.class)
        .lookupKeys(annotation -> keysOf((SampleNote) annotation))
        .coreParameters(
            CoreHandlerParameter.WORKFLOW_AGGREGATE,
            CoreHandlerParameter.TASK_PARAM,
            CoreHandlerParameter.MULTI_INSTANCE)
        .parameterBinder(SampleNoteContract::bindDetails)
        .parameterBinder(SampleNoteContract::bindEvent)
        .deliversReturnValue()
        .build();

  }

  /**
   * The keys one occurrence of the annotation names. Naming neither leaves the list
   * empty, which is VanillaBP's convention for "the method's own name".
   */
  private static List<String> keysOf(
      final SampleNote annotation) {

    final var keys = new LinkedList<String>();
    if (!annotation.element().equals(SampleNote.USE_METHOD_NAME)) {
      keys.add(annotation.element());
    }
    if (!annotation.taskDefinition().equals(SampleNote.USE_METHOD_NAME)) {
      keys.add(annotation.taskDefinition());
    }
    return keys;

  }

  /**
   * The prefilled note, recognized by its type.
   */
  private static Optional<HandlerValueSource> bindDetails(
      final HandlerParameter parameter) {

    return parameter.getType().equals(SampleNoteDetails.class)
        ? Optional.of(context -> context.getPayload())
        : Optional.empty();

  }

  /**
   * What happened to the element, recognized by its annotation. A parameter carrying the
   * annotation on the wrong type is a defect this binder REPORTS rather than rejects -
   * rejecting it would end the boot with "nothing can bind this parameter", which says
   * nothing about the mistake actually made.
   */
  private static Optional<HandlerValueSource> bindEvent(
      final HandlerParameter parameter) {

    if (!parameter.isAnnotationPresent(SampleNoteEvent.class)) {
      return Optional.empty();
    }
    if (!parameter.getType().equals(SampleNoteDetails.Kind.class)) {
      throw new IllegalStateException(
          """
              The %s is annotated with @SampleNoteEvent but is not of type \
              SampleNoteDetails.Kind! Change the parameter's type."""
              .formatted(parameter.describe()));
    }
    return Optional
        .of(context -> ((SampleNoteDetails) context.getPayload()).getKind());

  }

}
