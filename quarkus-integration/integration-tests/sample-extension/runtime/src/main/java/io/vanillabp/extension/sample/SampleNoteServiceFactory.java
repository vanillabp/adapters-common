package io.vanillabp.extension.sample;

import java.util.List;
import java.util.Optional;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.extension.spi.handler.HandlerCall;
import io.vanillabp.integration.extension.spi.service.AggregateServiceContext;
import io.vanillabp.integration.extension.spi.service.AggregateServiceFactory;

/**
 * Builds one {@link SampleNoteService} per workflow-aggregate class. VanillaBP calls it
 * when the application first injects the service, which is what makes injecting it
 * optional.
 */
public class SampleNoteServiceFactory implements AggregateServiceFactory<SampleNoteService> {

  /**
   * The key this extension reads from its own section of the configuration.
   */
  public static final String GREETING = "greeting";

  private final MigrationAdapterProperties properties;

  public SampleNoteServiceFactory(
      final MigrationAdapterProperties properties) {

    this.properties = properties;

  }

  @Override
  public Class<SampleNoteService> getServiceInterface() {

    return SampleNoteService.class;

  }

  @Override
  public SampleNoteService createService(
      final AggregateServiceContext context) {

    return new SampleNoteService<Object>() {

      @Override
      public Optional<SampleNoteDetails> noteOf(
          final Object workflowAggregate,
          final String elementId,
          final SampleNoteDetails.Kind kind) {

        // a note somebody reads is a read: VanillaBP is told not to save the aggregate
        return invoke(workflowAggregate, elementId, kind, false, false);

      }

      @Override
      public Optional<SampleNoteDetails> recordNoteOf(
          final Object workflowAggregate,
          final String elementId,
          final SampleNoteDetails.Kind kind) {

        return invoke(workflowAggregate, elementId, kind, true, false);

      }

      @Override
      public Optional<SampleNoteDetails> recordNoteInTheCallersTransaction(
          final Object workflowAggregate,
          final String elementId,
          final SampleNoteDetails.Kind kind) {

        return invoke(workflowAggregate, elementId, kind, true, true);

      }

      private Optional<SampleNoteDetails> invoke(
          final Object workflowAggregate,
          final String elementId,
          final SampleNoteDetails.Kind kind,
          final boolean saving,
          final boolean inTheCallersTransaction) {

        final var prefilled = new SampleNoteDetails(
            elementId, kind, "%s %s".formatted(configuredGreeting(), elementId));
        final var call = HandlerCall
            .of(SampleNote.class, context.getWorkflowModuleId(), context.getBpmnProcessId())
            .lookupKeys(List.of(elementId))
            .workflowAggregateId(context.getWorkflowAggregateId(workflowAggregate))
            .payload(prefilled)
            .variable("kind", kind.name());
        if (!saving) {
          call.withoutSavingTheWorkflowAggregate();
        }
        if (inTheCallersTransaction) {
          call.inTheCurrentTransaction();
        }
        return context
            .getHandlers()
            .invoke(call.build())
            .map(SampleNoteDetails.class::cast);

      }

      @Override
      public String bpmsHolding(
          final Object workflowAggregate) {

        return context
            .getElection()
            .adapterIdOfWorkflow(
                context.getWorkflowModuleId(),
                context.getBpmnProcessId(),
                context.getWorkflowAggregateId(workflowAggregate));

      }

      @Override
      public String configuredGreeting() {

        return properties
            .extensionProperty(context.getWorkflowModuleId(), SampleNoteContract.EXTENSION_ID, GREETING);

      }

    };

  }

}
