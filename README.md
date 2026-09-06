![Header](./readme/vanillabp-headline.png)

# VanillaBP platform integration

[![](https://img.shields.io/badge/Lifecycle-Incubating-blue)](https://github.com/Camunda-Community-Hub/community/blob/main/extension-lifecycle.md#incubating-)
[![Apache License V.2](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)

This repository contains platform integrations for the [VanillaBP SPI](https://github.com/vanillabp/spi-for-java).

VanillaBP aims to bring [hexagonal architecture](https://en.wikipedia.org/wiki/Hexagonal_architecture_(software))
to JVM-based business processing applications. Its primary goal is to enable the use of a business process management
system (BPMS) in a decoupled and maintainable way.  
To learn more, visit [https://www.vanillabp.io](https://www.vanillabp.io).

The VanillaBP SPI is an aspect-oriented API. Bringing it to life means integrating it into each
[supported platform](#documentation-and-supported-platforms) in a way that ensures an optimal developer experience
while respecting platform-specific conventions.

## Documentation and supported platforms

Currently, these platforms are supported:

1. **Spring Boot**<br>[![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fvanillabp.github.io%2Fadapter-platform-integration%2Fspring-boot-report%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://vanillabp.github.io/adapter-platform-integration/spring-boot-report)
2. **Quarkus**<br>[![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fvanillabp.github.io%2Fadapter-platform-integration%2Fquarkus-report%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://vanillabp.github.io/adapter-platform-integration/quarkus-report)

Developers who want to use VanillaBP should refer to the [Wiki](https://github.com/vanillabp/adapter-platform-integration/wiki).  
It contains conceptual documentation as well as platform-specific details.

The versions this release is built and tested against are Spring Boot **4.1.0**, Quarkus **3.37.1** and Java **21**.
Applications on a newer patch or minor of either platform are expected to work and are not tested here; a new major
is an upgrade of VanillaBP itself, and `UPGRADE.md` says what it took. There are no release lines per platform
version, unlike the
[Camunda 8 adapter](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter#release-lines), whose
artifacts carry the cluster minor: Camunda 8 forces that because the client a build was compiled against is the
lowest cluster version it accepts, while Spring Boot and Quarkus are dependencies of the application, which picks
them itself.

## Contribution

Developers who want to contribute to VanillaBP should familiarize themselves with the implementation details by
reading the `README.md` files of the respective submodules. Please create an [issue](./issues) first to let other
contributors know what you are working on.

The pictures which belong to those READMEs are catalogued in [`diagrams/README.md`](./diagrams):
the architecture overview as a drawing, plus a table saying which section of
`migration-adapter/README.md` holds each Mermaid diagram.

*Hint:* This repository is not about BPMS-specific adapters. It provides the infrastructure into which such adapters
plug in. To learn which BPMS are supported, visit [https://www.vanillabp.io](https://www.vanillabp.io), which also
links to the corresponding adapter repositories.

Writing an adapter for another BPMS, in a repository of your own? Read
[`migration-adapter/ADAPTER-AUTHORS.md`](./migration-adapter/ADAPTER-AUTHORS.md). It is written for a team without
access to this workspace and describes the two interfaces to implement, the calls the core expects back, what your
answers promise and what a wrong one costs.

### Where a snapshot comes from

Snapshots in GitHub Packages are published from `main`, and from nowhere else. There is one
`2.0.0-SNAPSHOT` per module, so a branch which published would overwrite what `main` published and
a consumer could end up resolving a set of modules that never existed in any single tree. That is
not theoretical: eighteen blueprint jobs failed on 2026-08-20 with a `NoSuchMethodError` naming a
method which existed on one story branch, because one module came from there and another from
`main`.

A pull request therefore builds and tests without deploying, and the publish job of `main` runs
under a concurrency group so two pushes cannot interleave. Please do not add a branch trigger to
try something out quickly; a locally installed snapshot does the same job without reaching anybody
else.

### Modules

Each `README.md` file lists information about its submodules and links to their respective README files.

Top-level modules (by directory name) are:

1. **bom:**<br>
   The BOM (artifact `io.vanillabp:vanillabp-bom`) applications import to get aligned versions of all VanillaBP
   artifacts. BPMS adapters are deliberately not part of it — they are released independently. Note that its parent is
   the release parent and *not* this aggregator: importing a BOM imports its *effective* dependency management, so
   inheriting from here would pin Lombok, SLF4J, Testcontainers, … in every application.
2. **migration-adapter:**<br>
   Core VanillaBP functionality shared across all platforms. The goal is to implement as much logic as possible in
   plain Java, without platform-specific dependencies. This helps deliver new features to all platforms with minimal
   effort. [Details...](./migration-adapter)
3. **spring-boot-integration:**<br>
   Support for using VanillaBP in Spring Boot applications. [Details...](./spring-boot-integration)
4. **quarkus-integration:**<br>
   Support for using VanillaBP in Quarkus applications. [Details...](./quarkus-integration)
5. **test-coverage-report:**<br>
   A module that generates test coverage reports. Click the [platform's badge](#documentation-and-supported-platforms)  to open the respective report.
   Its `coverage-gate` module is built last and breaks the build below the line
   (`coverage.threshold.spring-boot` and `coverage.threshold.quarkus` in the root POM, in percent of covered
   instructions - the same number the badge shows) or when a module produces coverage data no aggregated report reads.
   Both properties hold 85, the number every VanillaBP repository gates on. The rule is 90 per platform,
   so a report between the two passes the build and still names a gap somebody owes a test for.
   It reports what it measured on every run, green ones included, and is the one test class in VanillaBP
   which prints while it passes (`@PrintsWhenPassing`).
6. **test-utils:**<br>
   A small module providing utilities used by tests across all platforms.
7. **bpms-double:**<br>
   The BPMS double, published so that a repository outside this one can boot a VanillaBP application in a test
   without a real BPMS. It is the adapter the platform's own tests run against, which is what proves it for
   outsiders. [Details...](./bpms-double)

### Implementation

This repository contains the following VanillaBP functionality:

1. Platform integration:
   1. Loading VanillaBP configuration using the platform’s native configuration mechanisms, including the
      per-workflow-module configuration files and the conventions that make an explicit configuration optional.
   2. Detecting [workflow modules](https://github.com/vanillabp/adapter-platform-integration/wiki/Workflow-modules).
   3. Running the deployment pipeline of each workflow module (`readBpmn` → `prepareBpmn` → `wireBpmn` →
      `deployResources` → `startWorkflowProcessing`) for every configured adapter and
      [extension](https://github.com/vanillabp/adapter-platform-integration/wiki/Extensions). BPMN only — there is
      no DMN model type yet.
   4. Detecting services annotated with [@WorkflowService](https://github.com/vanillabp/spi-for-java#wire-up-a-process).
   5. Building a [ProcessService](https://github.com/vanillabp/spi-for-java#start-a-workflow) for each deployed process, to be used by workflow services.
   6. Executing [@WorkflowTask](https://github.com/vanillabp/spi-for-java#wire-up-a-task) methods on behalf of the
      adapters: resolving the handler, loading the workflow aggregate, binding the parameters, running the method in
      a transaction, saving the aggregate and mapping the outcome (completed / BPMN error / left open).
   7. Turning a workflow aggregate into the values a BPMS gets to see
      (`@SyncWithBPMS`/`@NoSyncWithBPMS`) — one model, used by every adapter.
   8. Reliable second phases for remote BPMS: the transaction outbox, its idempotency contract and the dispatch
      router.
   9. The read-only viewer/history API across all BPMS, incl. namespacing process-definition ids per adapter.
2. Support for migration across BPMS:
   1. Migrating within the same BPMS:
      1. From on-premise to SaaS.
      2. Between different versions.
   2. Migrating from one BPMS to another.
   3. Electing the BPMS holding a particular workflow by probing the configured adapters in priority order, and
      remembering the answer.

### Rules and decisions worth knowing before contributing

These are the conventions the current implementation follows. Keeping to them is what makes a new feature reach all
platforms at once — the details and their reasoning are in each module's `README.md` and in
[`UPGRADE.md`](./UPGRADE.md), which records every breaking change and why it was made.
They are not the citation targets of the code: what a comment points at is an entry of the
[decision log](./DECISIONS.md).

1. **Features are implemented in `migration-adapter`** (plain Java). A platform integration only does what only it
   can do: read configuration, scan/analyze business code, create beans, run transactions. If a feature needs
   platform-specific behavior, express it as an interface the core owns (e.g. `TransactionRunner`).
2. **Three SPIs, deliberately separated.** The *integration SPI* (`vanillabp-integration-spi`) is what applications
   implement (aggregate persistence, custom outbox, election cache). The *adapter SPI* (`vanillabp-adapter-spi`) is
   what BPMS adapters implement, and the *extension SPI* (`vanillabp-extension-spi`) carries the one interface an
   extension needs to take part in deploying a workflow module. A type never lives in more than one of them.
3. **One adapter instance per configured adapter id**, not per adapter type — that is the basis of the migration
   feature, and the reason process and deployment services exist per id.
4. **Validate at startup, guide in the message.** A configuration defect surfaces when the application boots, and
   the message names the property keys to add. An unconfigured application should still start and be led to a
   working setup by its own log.
5. **At-least-once, never pretended exactly-once.** Everything dispatched through the outbox may be dispatched
   again; operations are keyed for idempotency and residual windows are documented rather than hidden.
6. **Never read state back from the BPMS into the aggregate.** The aggregate is the single source of truth; the only
   values read from a BPMS are those a `@TaskParam` explicitly asks for.
7. **Tests: acceptance tests first**, per platform, against the published [BPMS double](./bpms-double); coverage is measured
   separately per platform (>90% of instructions, enforced by `test-coverage-report/coverage-gate`). A story is proven
   by its acceptance test, not by unit coverage.
8. **A sentence which promises behavior is part of the behavior.** A javadoc, `README.md` or wiki sentence promising
   something either has a test which fails when that stops being true, or it says that it is an assumption and what
   would disprove it. A story which changes behavior re-reads the claims about that behavior before it is done
   (the SPIs and the wikis were walked once to start from a clean state).

### Building

This project uses Java 21. In addition, `spotless` formatters are configured for all supported file
types to enforce a consistent code style.

Use the following command to build the project, run all tests, and generate the code coverage report:

```shell
mvn install
```

*Hint:* Note that `mvn install` is used instead of `mvn package`. This is
necessary because the Quarkus integration tests load modules not from the
`target` directories of submodules, but from the local Maven repository
populated by `mvn install`. Do not write `mvn install verify` either: `install`
already runs every phase `verify` has, so naming both walks two lifecycles per
module and reports every compiler warning twice.

## Decision log

Decisions several places in this repository rely on live in [`DECISIONS.md`](./DECISIONS.md), the
one thing the code is allowed to cite. A citation reads `see decision 7 in the repository's
DECISIONS.md`, numbers are never reused, and an overturned entry stays and names its successor, so
a citation written today still resolves in a year.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](./readme/phactum.png)

## License

Copyright 2026 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
