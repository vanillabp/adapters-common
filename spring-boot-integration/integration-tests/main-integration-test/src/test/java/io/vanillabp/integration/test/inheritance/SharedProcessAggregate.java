package io.vanillabp.integration.test.inheritance;

import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the two classes which split the handlers of one BPMN process between
 * them.
 */
@Getter
@Setter
public class SharedProcessAggregate {

  private String id;

  private String servedBy;

}
