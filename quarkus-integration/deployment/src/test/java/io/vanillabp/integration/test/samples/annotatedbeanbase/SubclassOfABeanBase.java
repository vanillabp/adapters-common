package io.vanillabp.integration.test.samples.annotatedbeanbase;

import jakarta.inject.Singleton;

/**
 * Inherits the declaration of a base which is a bean itself, so both classes serve the
 * BPMN process the base names.
 */
@Singleton
public class SubclassOfABeanBase extends AnnotatedBaseWhichIsABean {

}
