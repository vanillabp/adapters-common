package io.vanillabp.integration.test.inheritance;

import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the workflow service which inherits its declaration.
 */
@Getter
@Setter
public class InheritedAggregate {

  private String id;

  private String servedBy;

}
