package io.github.thedeadbolt.honeyfactory.world;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import java.time.Instant;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /honeyfactory world claim <world name>|status|release}: thin operator commands
 * over {@link WorldGuard} and {@link Baseline}. Scene and viewpoint operations go through
 * the client environment runtime instead.
 */
public final class WorldCommands {
	private WorldCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("honeyfactory")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(literal("world")
				.then(literal("claim").then(argument("world_name", StringArgumentType.greedyString()).executes(context -> run(context, source -> {
					WorldOwnership.Record record = WorldGuard.claim(source.getServer(), StringArgumentType.getString(context, "world_name"));
					return "Claimed '" + record.levelName() + "' as honey-factory dataset world " + record.worldId();
				}))))
				.then(literal("status").executes(context -> run(context, source -> {
					WorldOwnership.Record record = WorldGuard.requireOwned(source.getServer());
					return "Dataset world " + record.worldId() + " ('" + record.levelName() + "'), claimed " + Instant.ofEpochMilli(record.claimedAtEpochMillis())
						+ "; arena " + record.ownedArena() + "; baseline " + (record.baseline().isPresent() ? "applied, restore with /honeyfactory world release" : "not applied");
				})))
				.then(literal("release").executes(context -> run(context, source ->
					Baseline.restore(source.getServer()) ? "Restored the settings and players changed for dataset scenes" : "Nothing to restore"
				)))));
	}

	private static int run(CommandContext<CommandSourceStack> context, Function<CommandSourceStack, String> action) throws CommandSyntaxException {
		try {
			String message = action.apply(context.getSource());
			context.getSource().sendSuccess(() -> Component.literal(message), true);
			return 1;
		} catch (EnvironmentException e) {
			throw new SimpleCommandExceptionType(Component.literal(e.getMessage())).create();
		}
	}
}
