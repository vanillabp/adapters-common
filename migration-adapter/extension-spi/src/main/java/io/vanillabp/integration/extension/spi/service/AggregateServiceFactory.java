package io.vanillabp.integration.extension.spi.service;

/**
 * Lets an extension offer a service of its own the way VanillaBP offers
 * {@code ProcessService<A>}: one bean per workflow-aggregate class, injectable with the
 * aggregate as its type argument
 * (<code>&#64;Inject BusinessCockpitService&lt;Loan&gt;</code>).
 * <p>
 * An extension contributes the factory as a bean - on Spring Boot an ordinary one, on
 * Quarkus one its own extension produces. VanillaBP then creates one service per
 * workflow-aggregate class of the application, using the same universe
 * {@code ProcessService} beans are built for: the aggregates of the scanned
 * <code>&#64;WorkflowService</code> classes.
 * <p>
 * <b>Injecting it is optional.</b> Unlike {@code ProcessService}, nothing demands the
 * injection point - an application which never asks for the service simply never has one
 * built.
 *
 * @param <S> The service interface the extension offers, e.g.
 *          {@code BusinessCockpitService}
 */
public interface AggregateServiceFactory<S> {

  /**
   * The interface the application injects. It has to take the workflow aggregate as its
   * single type argument, since that is what the injection point names.
   *
   * @return The service interface
   */
  Class<S> getServiceInterface();

  /**
   * Builds the service of one workflow-aggregate class. Called once per aggregate, when
   * the application first injects the service.
   *
   * @param context The aggregate, its workflow and what a service usually needs
   * @return The service
   */
  S createService(
      AggregateServiceContext context);

}
