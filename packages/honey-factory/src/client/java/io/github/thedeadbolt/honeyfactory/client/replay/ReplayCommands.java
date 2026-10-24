package io.github.thedeadbolt.honeyfactory.client.replay;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.thedeadbolt.honeyfactory.environment.Futures;
import io.github.thedeadbolt.honeyfactory.replay.ReplayComparison;
import io.github.thedeadbolt.honeyfactory.replay.ReplayException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * {@code /honeyfactory_replay}: commands over {@link ReplayRuns}; they contain no replay
 * logic of their own.
 *
 * <pre>
 * load &lt;scene or sample id&gt; &lt;dataset directory&gt;   rebuild a recorded scene at the sample's viewpoint
 *                                               (the scene's first for a scene ID); a relative
 *                                               directory is taken from the game directory
 * viewpoint &lt;index&gt; | next | previous            move to another recorded viewpoint of the scene
 * capture                                       capture the current viewpoint and compare it
 * verify                                        capture and compare every viewpoint
 * status                                        the loaded replay
 * </pre>
 */
public final class ReplayCommands {
	private ReplayCommands() {
	}

	public static void initialize() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("honeyfactory_replay")
			.then(literal("load").then(argument("id", StringArgumentType.word()).then(argument("dataset", StringArgumentType.greedyString()).executes(context -> {
				FabricClientCommandSource source = context.getSource();
				Path directory = source.getClient().gameDirectory.toPath().resolve(StringArgumentType.getString(context, "dataset"));
				String id = StringArgumentType.getString(context, "id");
				return run(source, () -> ReplayRuns.load(directory, id), ReplayCommands::describe);
			}))))
			.then(literal("viewpoint")
				.then(argument("index", IntegerArgumentType.integer(0)).executes(context ->
					run(context.getSource(), () -> ReplayRuns.view(IntegerArgumentType.getInteger(context, "index")), ReplayCommands::describe)))
				.then(literal("next").executes(context -> run(context.getSource(), () -> ReplayRuns.view(offset(1)), ReplayCommands::describe)))
				.then(literal("previous").executes(context -> run(context.getSource(), () -> ReplayRuns.view(offset(-1)), ReplayCommands::describe))))
			.then(literal("capture").executes(context -> run(context.getSource(), ReplayRuns::capture, ReplayCommands::describe)))
			.then(literal("verify").executes(context -> run(context.getSource(), ReplayRuns::verify, ReplayCommands::describe)))
			.then(literal("status").executes(context -> {
				ReplayRuns.Loaded loaded = ReplayRuns.current();
				context.getSource().sendFeedback(Component.literal(loaded == null ? "No replay is loaded" : describe(loaded)));
				return 1;
			}))));
	}

	/** The viewpoint {@code step} away from the current one, wrapping around the scene. */
	private static int offset(int step) {
		ReplayRuns.Loaded loaded = ReplayRuns.current();
		if (loaded == null) {
			throw new IllegalStateException("no replay is loaded; use load first");
		}

		int count = loaded.scene().samples().size();
		return Math.floorMod(loaded.viewpoint() + step, count);
	}

	private static <T> int run(FabricClientCommandSource source, Supplier<CompletableFuture<T>> request, Function<T, String> describe) {
		CompletableFuture<T> future;
		try {
			future = request.get();
		} catch (ReplayException | IllegalStateException | IllegalArgumentException e) {
			source.sendError(Component.literal("Replay refused: " + e.getMessage()));
			return 0;
		}

		future.whenComplete((value, error) -> {
			if (error != null) {
				source.sendError(Component.literal("Replay failed: " + Futures.cause(error)));
			} else {
				source.sendFeedback(Component.literal(describe.apply(value)));
			}
		});
		return 1;
	}

	private static String describe(ReplayRuns.Loaded loaded) {
		String layout = loaded.layoutMatches() ? "layout checksum matches" : "LAYOUT CHECKSUM MISMATCH " + loaded.layoutSha256() + " (recorded " + loaded.scene().scene().layoutSha256() + ")";
		String render = loaded.renderDifferences().isEmpty() ? "render setup as recorded" : "render setup differs: " + String.join("; ", loaded.renderDifferences());
		return "Replaying " + loaded.scene().manifest().datasetId() + " " + loaded.sample().sampleId() + " (viewpoint " + loaded.viewpoint() + " of "
			+ loaded.scene().samples().size() + "): " + layout + ", " + render;
	}

	private static String describe(ReplayRuns.Verification verification) {
		StringBuilder message = new StringBuilder(verification.exact() ? "Exact replay" : "NOT an exact replay");
		for (ReplayComparison.Result result : verification.results()) {
			message.append("\n").append(result.sampleId()).append(": mask ")
				.append(result.maskMatches() ? "matches" : result.maskDifferingPixels() + " pixels differ (" + result.storedVisiblePixels() + " stored, " + result.freshVisiblePixels() + " fresh visible)")
				.append(", RGB ").append(result.rgbMatches() ? "matches" : result.rgbDifferingPixels() + " pixels differ by up to " + result.rgbMaxChannelDelta());
		}

		if (!verification.loaded().renderDifferences().isEmpty()) {
			message.append("\nRender setup differs from the dataset's: ").append(String.join("; ", verification.loaded().renderDifferences()));
		}

		return message.append("\nFresh images: ").append(verification.freshDirectory()).toString();
	}
}
