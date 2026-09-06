package io.vanillabp.bpmsdouble;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * The beans of one hook type a test declared, as the double sees them. It exists
 * because the two platforms answer "give me every bean of this type, there may be
 * none" with types the other one does not have: Spring Boot hands out an
 * {@code ObjectProvider}, CDI an {@code Instance}. Both of them already offer a
 * {@code stream()} of exactly this shape, so each platform module passes a method
 * reference and the behaviour of the double stays in one place.
 *
 * @param <H> The hook interface
 */
@FunctionalInterface
public interface HookBeans<H> {

  /**
   * @return Every bean of the hook type, in the order the platform resolves them
   */
  Stream<H> all();

  /**
   * The hook a test declared, where the double asks one question and one answer is
   * all it can use. Several beans are not an error here: the first one wins, the way
   * the platform ordered them.
   *
   * @return The first bean of the hook type, or empty where a test declared none
   */
  default Optional<H> first() {

    return all().findFirst();

  }

}
