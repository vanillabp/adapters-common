package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The guiding message of a failed build, picked out of the exception chain by its first
 * words: how deeply Quarkus wraps a build failure is Quarkus' business, not the
 * assertion's.
 */
final class GuidingMessage {

  private GuidingMessage() {
  }

  /**
   * @param failure The exception the build failed with
   * @param firstWords What the expected message starts with
   * @return The message
   */
  static String of(
      final Throwable failure,
      final String firstWords) {

    for (var current = failure; current != null; current = current.getCause()) {
      if ((current.getMessage() != null) && current.getMessage().contains(firstWords)) {
        return current.getMessage();
      }
    }
    return fail("no message starting with '%s' in the chain of: %s".formatted(firstWords, failure));

  }

}
