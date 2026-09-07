package io.vanillabp.extension.sample;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.extension.spi.handler.ExtensionHandlers;
import io.vanillabp.integration.extension.spi.service.AggregateServiceFactory;

/**
 * How the sample extension joins a Spring Boot application: two beans and one
 * registration.
 * <p>
 * The ordering is by NAME, so this module needs no dependency on the platform
 * integration - which is the point of the module (see its POM).
 */
@AutoConfiguration(afterName = "io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration")
public class SampleExtensionConfiguration implements InitializingBean {

  private final ExtensionHandlers handlers;

  public SampleExtensionConfiguration(
      final ExtensionHandlers handlers) {

    this.handlers = handlers;

  }

  /**
   * Registers what {@link SampleNote} means. Whether the workflow services were scanned
   * by now does not matter - a contract registered later is applied to the classes
   * registered so far.
   */
  @Override
  public void afterPropertiesSet() {

    handlers.register(SampleNoteContract.build());

  }

  /**
   * @param properties VanillaBP's configuration, which is where this extension's own
   *          section lives
   * @return The factory building one service per workflow aggregate
   */
  @Bean
  public AggregateServiceFactory<SampleNoteService> sampleNoteServiceFactory(
      final MigrationAdapterProperties properties) {

    return new SampleNoteServiceFactory(properties);

  }

}
