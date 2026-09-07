![Header](../../readme/vanillabp-headline.png)

# VanillaBP Quarkus extension - integration tests.

A collection of integration tests to guarantee desired functionality.

## Modules

1. **[native-image-tests](./native-image-tests):**<br>
   An application with a relational database and no MongoDB anywhere. It has no test class:
   building it is the assertion, in JVM mode on every build and as a native image where the
   profile `native-image` is active (`-Dnative`). Its main starts a workflow and reads the
   aggregate back, so running the binary checks the boot as well. See the section about
   optional extensions in the [runtime module's README](../runtime/README.md).
2. **[sample-extension](./sample-extension):**<br>
   An extension built like the VanillaBP Business Cockpit, in miniature - the Quarkus half
   of the extension the Spring Boot integration tests also build. It depends on the two SPI
   artifacts and the platform-neutral core and on no platform integration, which is what it
   exists to prove; `ExtensionEnablementTest` in
   [deployment-integration-tests](./deployment-integration-tests) exercises it.

The BPMS these tests run against is the published [BPMS double](../../bpms-double), which used to
live here as a sibling module.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](../../readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
