package io.vanillabp.integration.test.outbox;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bson.BsonValue;

import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;

/**
 * Every command the driver sends, so a test can claim that a collection was not touched.
 * <p>
 * The driver reports a command before it goes out, which is the number an application pays
 * for. Its own topology monitoring does not come through here - those commands travel on
 * connections of their own - so what is counted is what the application asked for.
 */
public class CountingCommandListener implements CommandListener {

  /**
   * One entry per command, as "command name on collection".
   */
  private final List<String> commands = new CopyOnWriteArrayList<>();

  @Override
  public void commandStarted(
      final CommandStartedEvent event) {

    commands.add("%s on %s".formatted(event.getCommandName(), collectionOf(event)));

  }

  /**
   * The collection a command names. A command document starts with its own name mapped to
   * the collection it is about, which is where this reads it; a command about the database
   * rather than about a collection answers an empty name.
   *
   * @param event The command which started
   * @return The collection or an empty string
   */
  private static String collectionOf(
      final CommandStartedEvent event) {

    final var command = event.getCommand();
    final BsonValue target = command.get(command.getFirstKey());
    return (target != null) && target.isString()
        ? target.asString().getValue()
        : "";

  }

  /**
   * The commands sent since the last {@link #reset()} which are about the given collection.
   *
   * @param collection The collection to look for
   * @return The commands, empty where the collection was not touched
   */
  public List<String> commandsOn(
      final String collection) {

    return commands
        .stream()
        .filter(command -> command.endsWith(" on "
            + collection))
        .toList();

  }

  public void reset() {

    commands.clear();

  }

}
