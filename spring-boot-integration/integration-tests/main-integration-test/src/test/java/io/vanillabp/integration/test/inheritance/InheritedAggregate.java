package io.vanillabp.integration.test.inheritance;

import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the workflow service which inherits its declaration. It lives in a
 * package of its own so no other test's context registers it.
 */
@Getter
@Setter
public class InheritedAggregate {

  private String id;

  private String servedBy;

}
