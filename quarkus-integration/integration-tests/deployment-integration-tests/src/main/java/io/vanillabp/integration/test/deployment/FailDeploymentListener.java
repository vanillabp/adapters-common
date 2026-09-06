package io.vanillabp.integration.test.deployment;

import org.eclipse.microprofile.config.ConfigProvider;

import io.vanillabp.bpmsdouble.DummyDeploymentListener;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Makes the dummy adapter's <code>deployResources</code> fail for the adapter id
 * configured by the property <code>test.fail-deploy-for</code> - used to test the
 * <code>vanillabp.adapters.&lt;id&gt;.deployment-failure</code> policy. The failure
 * must be configured declaratively (not from test code) because it has to strike
 * during startup, before any test method runs.
 */
@ApplicationScoped
public class FailDeploymentListener implements DummyDeploymentListener {

  public static final String PROPERTY_FAIL_DEPLOY_FOR = "test.fail-deploy-for";

  @Override
  public void onPipelineCall(
      final String adapterId,
      final String method,
      final String workflowModuleId,
      final String detail) {

    if (!"deployResources".equals(method)) {
      return;
    }
    final var failFor = ConfigProvider
        .getConfig()
        .getOptionalValue(PROPERTY_FAIL_DEPLOY_FOR, String.class)
        .orElse(null);
    if (adapterId.equals(failFor)) {
      throw new RuntimeException(
          "deployment failed for testing purposes (adapter '%s')".formatted(adapterId));
    }

  }

}
