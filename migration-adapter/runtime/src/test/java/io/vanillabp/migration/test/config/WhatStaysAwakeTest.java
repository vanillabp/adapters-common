package io.vanillabp.migration.test.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An application which lets its outbox sleep watches its database next, and this is the
 * message it reads first. Promising silence would be the easy answer and the wrong one: what
 * the developer needs is the list of timers which are still there, because every one they do
 * not hear about here they have to find themselves.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WhatStaysAwakeTest {

  private ListAppender<ILoggingEvent> logWatcher;

  @BeforeEach
  public void watchTheLog() {

    logWatcher = new ListAppender<>();
    logWatcher.start();
    ((Logger) LoggerFactory.getLogger(MigrationAdapterProperties.class)).addAppender(logWatcher);

  }

  @AfterEach
  public void stopWatchingTheLog() {

    ((Logger) LoggerFactory.getLogger(MigrationAdapterProperties.class)).detachAndStopAllAppenders();

  }

  private String loggedLines() {

    return logWatcher.list
        .stream()
        .map(ILoggingEvent::getFormattedMessage)
        .collect(java.util.stream.Collectors.joining("\n"));

  }

  /**
   * A configuration which validates, with one adapter of the given type and one workflow
   * module.
   *
   * @param adapterType The type of the single configured adapter
   * @param pollInterval What the cap on the outbox sleep is set to, or <code>null</code> to
   *          leave the default alone
   */
  private static void validate(
      final String adapterType,
      final Duration pollInterval) {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("the-engine", AdapterConfigProperties.ofType(adapterType)));
    properties.setPrioritizedAdapters(List.of("the-engine"));
    properties.setWorkflowModules(Map.of("a-module", WorkflowModuleAdapterProperties
        .builder()
        .workflowModuleId("a-module")
        .build()));
    if (pollInterval != null) {
      properties.getOutbox().setPollInterval(pollInterval);
    }
    properties.validateProperties(List.of(adapterType), List.of("a-module"));

  }

  @Test
  @DisplayName("An application which keeps the default is told nothing")
  public void theDefaultSaysNothing() {

    validate("camunda7", null);

    assertFalse(
        loggedLines().contains("is still awake"),
        "an application which changed nothing asked no question and gets no answer: "
            + loggedLines());

  }

  @Test
  @DisplayName("A longer cap is answered with the timers which are still there")
  public void aLongerCapIsAnsweredWithWhatRemains() {

    validate("dummy", Duration.ofMinutes(10));

    final var logged = loggedLines();
    assertTrue(logged.contains("'vanillabp.outbox.poll-interval' is PT10M"), logged);
    assertTrue(logged.contains("instead of the default PT10S"), logged);
    assertTrue(
        logged.contains("retention cleanup of the task-delivery records"),
        "the hourly cleanup is a timer the reader has to know about: "
            + logged);
    assertTrue(
        logged.contains("vanillabp.outbox.pending"),
        "a gauge which counts rows while somebody scrapes is traffic as well: "
            + logged);
    assertTrue(
        logged.contains("vanillabp.metrics.gauge-cache"),
        "the message names what bounds that counting: "
            + logged);
    assertTrue(
        logged.contains("that adapter's own business"),
        "an adapter's own polling is named even where VanillaBP cannot put numbers on it: "
            + logged);

  }

  @Test
  @DisplayName("A Camunda 7 adapter is named with the numbers its engine keeps")
  public void aCamunda7EngineIsNamedWithItsOwnNumbers() {

    validate("camunda7", Duration.ofMinutes(10));

    final var logged = loggedLines();
    assertTrue(
        logged.contains("the Camunda 7 engine of adapter 'the-engine'"),
        "the message names the adapter id, because a migration setup has more than one: "
            + logged);
    assertTrue(logged.contains("every 5 to 60 seconds"), logged);
    assertTrue(
        logged.contains("every 900 seconds"),
        "the engine's metrics reporter writes on a timer of its own, and that is the one nobody "
            + "expects: "
            + logged);

  }

  @Test
  @DisplayName("An application without a Camunda 7 adapter hears nothing about an engine it does not run")
  public void anotherBpmsIsNotToldAboutCamunda7() {

    validate("dummy", Duration.ofMinutes(10));

    assertFalse(loggedLines().contains("Camunda 7 engine"), loggedLines());

  }

}
