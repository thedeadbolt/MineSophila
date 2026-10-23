package io.github.thedeadbolt.honeyfactory.client.generation;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.ContractException;
import io.github.thedeadbolt.honeyfactory.generation.DatasetGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * {@code /honeyfactory_dataset}: commands over {@link DatasetRuns}; they contain no
 * generation logic of their own.
 *
 * <pre>
 * generate &lt;config path&gt;   resolve a JSON configuration (relative to the game directory) and start
 * status                    progress of the running or last generation
 * cancel                    stop the running generation; the dataset is marked failed
 * </pre>
 */
public final class DatasetCommands {
	private DatasetCommands() {
	}

	public static void initialize() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("honeyfactory_dataset")
			.then(literal("generate").then(argument("config", StringArgumentType.greedyString()).executes(context -> {
				FabricClientCommandSource source = context.getSource();
				try {
					Path path = source.getClient().gameDirectory.toPath().resolve(StringArgumentType.getString(context, "config"));
					ResolvedConfig config = ConfigResolver.resolve(Files.readString(path, StandardCharsets.UTF_8));
					DatasetRuns.start(config).whenComplete((result, error) -> {
						if (result != null && result.failure() == null) {
							source.sendFeedback(Component.literal("Dataset " + result.manifest().datasetId() + " complete: " + result.directory()));
						} else {
							source.sendError(Component.literal("Dataset generation failed: " + (result != null ? result.failure() : String.valueOf(error))));
						}
					});
					source.sendFeedback(Component.literal("Generating " + config.datasetId() + " into " + config.outputRoot()));
					return 1;
				} catch (IOException | ContractException | IllegalStateException | UnsupportedOperationException e) {
					source.sendError(Component.literal("Cannot start: " + e.getMessage()));
					return 0;
				}
			})))
			.then(literal("status").executes(context -> {
				DatasetGenerator generator = DatasetRuns.running();
				String message;
				if (generator != null) {
					DatasetGenerator.Progress progress = generator.progress();
					message = "Running: " + progress.acceptedScenes() + "/" + progress.requestedScenes() + " scenes, " + progress.acceptedSamples() + " samples, "
						+ progress.attemptedScenes() + " attempts, " + progress.rejectedScenes() + " rejected";
				} else if (DatasetRuns.last() != null) {
					DatasetGenerator.Result last = DatasetRuns.last();
					message = "Last: " + last.manifest().datasetId() + " " + last.manifest().lifecycle().jsonName() + (last.failure() == null ? "" : " (" + last.failure() + ")");
				} else {
					message = "No dataset generation has run";
				}

				context.getSource().sendFeedback(Component.literal(message));
				return 1;
			}))
			.then(literal("cancel").executes(context -> {
				DatasetGenerator generator = DatasetRuns.running();
				if (generator == null) {
					context.getSource().sendError(Component.literal("No dataset generation is running"));
					return 0;
				}

				generator.cancel();
				context.getSource().sendFeedback(Component.literal("Cancelling after the current step"));
				return 1;
			}))));
	}
}
