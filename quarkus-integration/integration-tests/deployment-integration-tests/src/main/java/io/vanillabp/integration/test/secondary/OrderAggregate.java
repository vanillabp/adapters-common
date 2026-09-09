package io.vanillabp.integration.test.secondary;

import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the called-process test. One aggregate carries both BPMN processes of
 * its workflow service, which is what makes a record of the called process a statement
 * about the workflow the primary process addresses.
 */
@Getter
@Setter
public class OrderAggregate {

  private String id;

  private String status;

}
