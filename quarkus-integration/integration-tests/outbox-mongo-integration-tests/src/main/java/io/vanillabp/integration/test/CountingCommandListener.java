package io.vanillabp.integration.test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bson.BsonValue;

import com.mongodb.MongoClientSettings;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;

import io.quarkus.mongodb.runtime.MongoClientCustomizer;
import jakarta.inject.Singleton;

/**
 * Every command the application's MongoDB client sends, so a test can claim that a
 * collection was not touched.
 * <p>
 * The driver reports a command before it goes out, which is the number an application pays
 * for. Its own topology monitoring does not come through here - those commands travel on
 * connections of their own - so what is counted is what the application asked for.
 * <p>
 * The commands are held statically because this is the client's customizer, built while the
 * application starts, while the test which reads them lives outside the application.
 */
@Singleton
public class CountingCommandListener implements MongoClientCustomizer, CommandListener {

  /**
   * One entry per command, as "command name on collection".
   */
  private static final List<String> COMMANDS = new CopyOnWriteArrayList<>();

  @Override
  public MongoClientSettings.Builder customize(
      final MongoClientSettings.Builder builder) {

    return builder.addCommandListener(this);

  }

  @Override
  public void commandStarted(
      final CommandStartedEvent event) {

    COMMANDS.add("%s on %s".formatted(event.getCommandName(), collectionOf(event)));

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
   * The commands sent since the last {@link #forgetWhatWasSent()} which are about the given
   * collection.
   *
   * @param collection The collection to look for
   * @return The commands, empty where the collection was not touched
   */
  public static List<String> commandsOn(
      final String collection) {

    return COMMANDS
        .stream()
        .filter(command -> command.endsWith(" on "
            + collection))
        .toList();

  }

  public static void forgetWhatWasSent() {

    COMMANDS.clear();

  }

}
