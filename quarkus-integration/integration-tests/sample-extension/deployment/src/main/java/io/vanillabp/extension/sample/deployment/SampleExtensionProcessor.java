package io.vanillabp.extension.sample.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.AdditionalIndexedClassesBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.vanillabp.extension.sample.SampleExtensionProducer;
import io.vanillabp.extension.sample.SampleNoteServiceFactory;

/**
 * The sample extension as a Quarkus extension: it announces no build item of its own -
 * registering the producer of its factory and of its handler contract is all an extension
 * has to do.
 */
class SampleExtensionProcessor {

  private static final String FEATURE = "vanillabp-sample-extension";

  /**
   * @param featureProducer Feature build item producer
   * @return The bean registration build item
   */
  @BuildStep
  AdditionalBeanBuildItem registerProducer(
      final BuildProducer<FeatureBuildItem> featureProducer) {

    featureProducer.produce(new FeatureBuildItem(FEATURE));

    return AdditionalBeanBuildItem
        .builder()
        .addBeanClass(SampleExtensionProducer.class)
        .setUnremovable()
        .build();

  }

  /**
   * Puts the factory into the index VanillaBP reads to find out which service interface
   * this extension offers. A runtime module carrying a Jandex index needs none of this;
   * this one does not build one, so it says which class matters.
   *
   * @return The class to index
   */
  @BuildStep
  AdditionalIndexedClassesBuildItem indexTheFactory() {

    return new AdditionalIndexedClassesBuildItem(SampleNoteServiceFactory.class.getName());

  }

}
