package io.vanillabp.integration.deployment.processservice;

import java.util.List;

import org.jboss.jandex.DotName;

import io.quarkus.builder.item.SimpleBuildItem;

/**
 * The workflow-aggregate classes of this application - the same universe
 * {@code ProcessService} beans are built for, published so that a second build step can
 * build one bean per aggregate as well without scanning the archives again.
 * <p>
 * That second build step is the one giving an extension its own per-aggregate service
 * (see {@code ExtensionServiceBuildStepProcessor}).
 */
public final class VanillaBpWorkflowAggregatesBuildItem extends SimpleBuildItem {

  private final List<DotName> workflowAggregateClasses;

  public VanillaBpWorkflowAggregatesBuildItem(
      final List<DotName> workflowAggregateClasses) {

    this.workflowAggregateClasses = List.copyOf(workflowAggregateClasses);

  }

  /**
   * @return The workflow-aggregate classes, in the order the archives were scanned
   */
  public List<DotName> getWorkflowAggregateClasses() {

    return workflowAggregateClasses;

  }

}
