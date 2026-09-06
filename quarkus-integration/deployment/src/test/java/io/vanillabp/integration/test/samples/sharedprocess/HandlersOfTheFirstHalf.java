package io.vanillabp.integration.test.samples.sharedprocess;

import jakarta.inject.Singleton;

/**
 * One of two classes splitting the handlers of one BPMN process, both of them workflow
 * services by inheritance.
 */
@Singleton
public class HandlersOfTheFirstHalf extends SharedProcessBase {

}
