package io.vanillabp.integration.test.discovery;

import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of {@link ForgottenWorkflowService} - never persisted by anything, because
 * the class declaring it never became a bean.
 */
@Getter
@Setter
public class ForgottenAggregate {

  private String id;

}
