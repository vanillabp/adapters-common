package io.vanillabp.integration.adapter.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync.PathVerdict;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What an adapter asking about a path hears where nothing behind the SPI answers. Every
 * one of these questions decides NOTHING by default, which is the answer a check has to
 * be able to act on: a path missing from the answer never means "checked and found
 * working", and a path with no type never means "this type is safe".
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowAggregateSyncPathDefaultsTest {

  @Test
  @DisplayName("The sync model decides nothing about a path unless an implementation walks it")
  public void theDefaultDecidesNothing() {

    final var undecided = mock(WorkflowAggregateSync.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))
        .whatAPathFinds(String.class, List.of("order", "internalCode"), AggregateSyncMode.FULL);

    assertEquals(PathVerdict.Kind.UNDECIDABLE, undecided.kind());
    assertFalse(undecided.pathIsCut(), "an undecided path must never be reported");

  }

  @Test
  @DisplayName("The sync model names no type unless an implementation walks the path")
  public void theDefaultNamesNoType() {

    assertTrue(
        mock(WorkflowAggregateSync.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))
            .whatTypeAPathEndsAt(String.class, List.of("order", "total"), AggregateSyncMode.FULL)
            .isEmpty(),
        "a type nobody walked to must not be judged");

  }

  @Test
  @DisplayName("A core which cannot walk a path names no type of one either")
  public void theWiringDefaultNamesNoType() {

    assertEquals(
        Map.of(),
        mock(WorkflowTaskWiring.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))
            .declaredTypesOfWorkflowAggregatePaths(
                "module",
                "Process",
                List.of("order.total"),
                AggregateSyncMode.FULL));

  }

  @Test
  @DisplayName("A core which cannot walk a path reports none of them")
  public void theWiringDefaultReportsNothing() {

    assertEquals(
        Map.of(),
        mock(WorkflowTaskWiring.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))
            .unsharedWorkflowAggregatePaths(
                "module",
                "Process",
                List.of("order.internalCode"),
                AggregateSyncMode.FULL));

  }

  @Test
  @DisplayName("Only a cut path is worth a word")
  public void onlyACutPathIsReportable() {

    assertFalse(PathVerdict.aSharedValue().pathIsCut());
    assertFalse(PathVerdict.undecidable().pathIsCut());
    assertTrue(PathVerdict.notShared("internalCode", 1, "Order").pathIsCut());
    assertTrue(PathVerdict.noSuchAttribute("town", 2, "Customer").pathIsCut());
    assertTrue(PathVerdict.nothingBelow("year", 2, "LocalDate").pathIsCut());
    assertEquals(-1, PathVerdict.aSharedValue().segmentIndex(), "a path which stops nowhere names no segment");

  }

}
