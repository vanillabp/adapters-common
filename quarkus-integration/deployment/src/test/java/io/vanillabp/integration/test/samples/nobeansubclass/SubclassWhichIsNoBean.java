package io.vanillabp.integration.test.samples.nobeansubclass;

/**
 * Inherits the declaration and carries no bean-defining annotation, so VanillaBP has a
 * workflow service it can never get an instance of.
 */
public class SubclassWhichIsNoBean extends DeclarationOfANoBeanSubclass {

}
