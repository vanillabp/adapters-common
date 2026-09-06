![Header](../../readme/vanillabp-headline.png)

# VanillaBP Spring Boot support - integration tests.

A collection of integration tests to guarantee desired functionality.

## Modules

1. **[dummy-extension](./dummy-extension):**<br>
   This adapter is a template for new extensions and is used as an extension
   by the integration tests. For ready-to-use adapter extensions checkout
   [https://www.vanillabp.io](https://www.vanillabp.io).
2. **[main-integration-test](./main-integration-test):**<br>
   This module contains the main integration test.
3. **[test-applications](./test-applications):**<br>
   This module contains the test applications used by integration tests.
4. **[workflowmodule-integration-tests](./workflowmodule-integration-tests):**<br>
   This module contains integration tests, testing workflow module functionality.

The BPMS these tests run against is the published [BPMS double](../../bpms-double), which used to
live here as a sibling module. For ready-to-use adapter modules checkout
[https://www.vanillabp.io](https://www.vanillabp.io).

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](../../readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
