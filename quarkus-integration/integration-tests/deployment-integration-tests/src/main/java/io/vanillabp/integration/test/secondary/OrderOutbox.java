package io.vanillabp.integration.test.secondary;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A store of the application's own which never dispatches, so the test decides when phase
 * two runs and what the BPMS was asked before it.
 */
@ApplicationScoped
public class OrderOutbox implements PhaseTwoOutbox {

  private final List<PhaseTwoCall> planned = new CopyOnWriteArrayList<>();

  /**
   * @return Everything planned so far, in order
   */
  public List<PhaseTwoCall> planned() {

    return List.copyOf(planned);

  }

  public void clear() {

    planned.clear();

  }

  @Override
  public boolean schedule(
      final PhaseTwoCall call) {

    return planned.add(call);

  }

}
