package io.vanillabp.bpmsdouble;

/**
 * A phase-two failure this adapter reports as permanent: the store blocks
 * the outbox entry instead of retrying it until the configured attempts are used up.
 */
public class DummyPermanentFailure extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public DummyPermanentFailure(
      final String message) {

    super(message);

  }

}
