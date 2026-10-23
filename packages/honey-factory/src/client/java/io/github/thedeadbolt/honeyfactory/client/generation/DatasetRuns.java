package io.github.thedeadbolt.honeyfactory.client.generation;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.thedeadbolt.honeyfactory.HoneyFactory;
import io.github.thedeadbolt.honeyfactory.client.capture.CaptureService;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.ContractException;
import io.github.thedeadbolt.honeyfactory.generation.DatasetGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Runs one dataset generation at a time in the running client, and the
 * {@code /honeyfactory_dataset} commands that control it.
 *
 * <pre>
 * generate &lt;config path&gt;   resolve a JSON configuration (relative to the game directory) and start
 * status                    progress of the running or last generation
 * cancel                    stop the running generation; the dataset is marked failed
 * </pre>
 */
public final class DatasetRuns {
	private static @Nullable DatasetGenerator running;
	private static DatasetGenerator.@Nullable Result last;

	private DatasetRuns() {
	}

	/**
	 * Starts generating {@code config} in the current dataset world.
	 *
	 * @throws IllegalStateException if a generation is running or improved transparency is off
	 * @throws IOException if the dataset directory cannot be created, including when it exists
	 */
	public static CompletableFuture<DatasetGenerator.Result> start(ResolvedConfig config) throws IOException {
		if (running != null) {
			throw new IllegalStateException("a dataset generation is already running");
		}

		if (!Minecraft.getInstance().options.improvedTransparency().get()) {
			throw new IllegalStateException(CaptureService.IMPROVED_TRANSPARENCY_REQUIRED);
		}

		DatasetGenerator generator = new DatasetGenerator(config, new RuntimeGenerationEnvironment(EnvironmentRuntime.get()), Clock.systemUTC());
		CompletableFuture<DatasetGenerator.Result> result = generator.start();
		running = generator;
		return result.whenComplete((value, error) -> {
			running = null;
			last = value;
			if (error != null) {
				HoneyFactory.LOGGER.error("Dataset generation ended with an error", error);
			}
		});
	}

	public static @Nullable DatasetGenerator running() {
		return running;
	}

	public static DatasetGenerator.@Nullable Result last() {
		return last;
	}

	public static void initialize() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("honeyfactory_dataset")
			.then(literal("generate").then(argument("config", StringArgumentType.greedyString()).executes(context -> {
				FabricClientCommandSource source = context.getSource();
				try {
					Path path = source.getClient().gameDirectory.toPath().resolve(StringArgumentType.getString(context, "config"));
					ResolvedConfig config = ConfigResolver.resolve(Files.readString(path, StandardCharsets.UTF_8));
					start(config).whenComplete((result, error) -> {
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
				DatasetGenerator generator = running;
				String message;
				if (generator != null) {
					DatasetGenerator.Progress progress = generator.progress();
					message = "Running: " + progress.acceptedScenes() + "/" + progress.requestedScenes() + " scenes, " + progress.acceptedSamples() + " samples, "
						+ progress.attemptedScenes() + " attempts, " + progress.rejectedScenes() + " rejected";
				} else if (last != null) {
					message = "Last: " + last.manifest().datasetId() + " " + last.manifest().lifecycle().jsonName() + (last.failure() == null ? "" : " (" + last.failure() + ")");
				} else {
					message = "No dataset generation has run";
				}

				context.getSource().sendFeedback(Component.literal(message));
				return 1;
			}))
			.then(literal("cancel").executes(context -> {
				DatasetGenerator generator = running;
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
