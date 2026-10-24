package io.github.thedeadbolt.honeyfactory.client.runtime;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.thedeadbolt.honeyfactory.capture.CaptureDebugFiles;
import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.capture.CubeFace;
import io.github.thedeadbolt.honeyfactory.capture.PanoramicProjection;
import io.github.thedeadbolt.honeyfactory.capture.PerspectiveProjection;
import io.github.thedeadbolt.honeyfactory.contract.ContractException;
import io.github.thedeadbolt.honeyfactory.contract.Ids;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import io.github.thedeadbolt.honeyfactory.contract.MaskStatistics;
import io.github.thedeadbolt.honeyfactory.contract.SceneSeeds;
import io.github.thedeadbolt.honeyfactory.environment.Action;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentSettings;
import io.github.thedeadbolt.honeyfactory.environment.Futures;
import io.github.thedeadbolt.honeyfactory.environment.OperationCoordinator;
import io.github.thedeadbolt.honeyfactory.scene.FixturePlans;
import io.github.thedeadbolt.honeyfactory.scene.SceneBase;
import io.github.thedeadbolt.honeyfactory.scene.ScenePresets;
import io.github.thedeadbolt.honeyfactory.scene.ScenePreview;
import io.github.thedeadbolt.honeyfactory.scene.SceneSpec;
import io.github.thedeadbolt.honeyfactory.world.NaturalPatches;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;

/**
 * {@code /honeyfactory_env}: debug commands that call {@link EnvironmentRuntime}; they
 * contain no environment logic of their own.
 *
 * <pre>
 * reset &lt;fixture&gt;                          build a fixture plan and move to its viewpoint
 * reset preset &lt;name&gt; [seed [index]]        build scene index (default 0) of a preset recipe and
 *                                           move to its first viewpoint; seed defaults to 0
 * reset recipe &lt;file&gt; [seed [index]]        the same for a recipe file in the game directory,
 *                                           written like a configuration's scene section
 * reset course                              build the movement course and stand at its step lane
 * viewpoint fixture | &lt;x y z yaw pitch&gt;      place the camera (feet position)
 * viewpoint course &lt;lane&gt;                   stand at the start of a course lane, facing north
 * observe &lt;label&gt; &lt;width&gt; &lt;height&gt;          capture RGB and masks into honey-factory-debug/&lt;label&gt;
 * observe &lt;label&gt; &lt;width&gt; &lt;height&gt; panorama &lt;face size&gt;
 *                                           the same for PANORAMA_310, with each cube face in faces/
 * step turn|look &lt;degrees&gt;                 turn right / look down by degrees (negative for left / up)
 * step move_forward|wait &lt;ticks&gt;           hold forward / hold nothing for player ticks
 * step stop                                 release every input
 * step mine &lt;ticks&gt;                         rejected: mining is unsupported
 *                                           each step captures a 320 x 240 perspective view
 * settings [autojump &lt;true|false&gt;]          show settings, or set auto-jump for later steps
 * history                                   the last finished operations, in order
 * status
 * </pre>
 */
public final class EnvironmentCommands {
	public static final String DEBUG_DIRECTORY = "honey-factory-debug";

	private static final Pattern LABEL = Pattern.compile("[a-z0-9_-]{1,64}");
	private static final PerspectiveProjection STEP_PROJECTION = new PerspectiveProjection(320, 240);
	private static final int HISTORY_LINES = 10;

	private EnvironmentCommands() {
	}

	public static void initialize() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			LiteralArgumentBuilder<FabricClientCommandSource> reset = literal("reset");
			for (FixturePlans.Fixture fixture : FixturePlans.Fixture.values()) {
				reset.then(literal(fixture.commandName()).executes(context -> report(context, EnvironmentRuntime.get().reset(
					FixturePlans.ARENA, FixturePlans.plan(fixture), FixturePlans.viewpoint(fixture).pose()
				), scene -> "Reset " + fixture.commandName() + " (" + scene.operationId() + "), layout " + scene.layoutSha256())));
			}

			reset.then(literal("course").executes(context -> report(context, EnvironmentRuntime.get().reset(
				FixturePlans.ARENA, FixturePlans.course(), FixturePlans.courseStart(FixturePlans.Lane.STEP).pose()
			), scene -> "Reset course (" + scene.operationId() + "), layout " + scene.layoutSha256())));
			LiteralArgumentBuilder<FabricClientCommandSource> courseLanes = literal("course");
			for (FixturePlans.Lane lane : FixturePlans.Lane.values()) {
				courseLanes.then(literal(lane.name().toLowerCase(Locale.ROOT)).executes(context -> viewpoint(context, FixturePlans.courseStart(lane).pose())));
			}

			LiteralArgumentBuilder<FabricClientCommandSource> step = literal("step");
			for (String type : List.of("turn", "look", "move_forward", "wait", "mine")) {
				step.then(literal(type).then(argument("value", DoubleArgumentType.doubleArg()).executes(context -> step(context, type, DoubleArgumentType.getDouble(context, "value")))));
			}

			step.then(literal("stop").executes(context -> step(context, "stop", 0.0)));

			reset.then(literal("preset").then(argument("name", StringArgumentType.word())
				.suggests((context, builder) -> {
					ScenePresets.names().forEach(builder::suggest);
					return builder.buildFuture();
				})
				.executes(context -> preview(context, false))
				.then(argument("seed", LongArgumentType.longArg(0, SceneSeeds.MAX_SEED)).executes(context -> preview(context, false))
					.then(argument("index", IntegerArgumentType.integer(0, Ids.MAX_SCENES - 1)).executes(context -> preview(context, false))))));
			reset.then(literal("recipe").then(argument("file", StringArgumentType.string())
				.executes(context -> preview(context, true))
				.then(argument("seed", LongArgumentType.longArg(0, SceneSeeds.MAX_SEED)).executes(context -> preview(context, true))
					.then(argument("index", IntegerArgumentType.integer(0, Ids.MAX_SCENES - 1)).executes(context -> preview(context, true))))));

			dispatcher.register(literal("honeyfactory_env")
				.then(reset)
				.then(literal("viewpoint")
					.then(literal("fixture").executes(context -> viewpoint(context, FixturePlans.VIEWPOINT.pose())))
					.then(courseLanes)
					.then(argument("x", DoubleArgumentType.doubleArg()).then(argument("y", DoubleArgumentType.doubleArg()).then(argument("z", DoubleArgumentType.doubleArg())
						.then(argument("yaw", FloatArgumentType.floatArg(-180.0F, 180.0F)).then(argument("pitch", FloatArgumentType.floatArg(-90.0F, 90.0F))
							.executes(context -> viewpoint(context, new CameraPose(
								DoubleArgumentType.getDouble(context, "x"),
								DoubleArgumentType.getDouble(context, "y"),
								DoubleArgumentType.getDouble(context, "z"),
								FloatArgumentType.getFloat(context, "yaw"),
								FloatArgumentType.getFloat(context, "pitch")
							)))))))))
				.then(literal("observe").then(argument("label", StringArgumentType.word()).then(argument("width", IntegerArgumentType.integer(PerspectiveProjection.MIN_SIZE, 4096))
					.then(argument("height", IntegerArgumentType.integer(PerspectiveProjection.MIN_SIZE, 4096)).executes(EnvironmentCommands::observe)
						.then(literal("panorama").then(argument("face", IntegerArgumentType.integer(PanoramicProjection.MIN_SIZE, PanoramicProjection.MAX_FACE_SIZE))
							.executes(EnvironmentCommands::observe)))))))
				.then(step)
				.then(literal("settings").executes(EnvironmentCommands::showSettings)
					.then(literal("autojump").then(argument("enabled", BoolArgumentType.bool()).executes(context -> {
						try {
							EnvironmentRuntime runtime = EnvironmentRuntime.get();
							runtime.configure(runtime.settings().withAutoJump(BoolArgumentType.getBool(context, "enabled")));
						} catch (EnvironmentException e) {
							context.getSource().sendError(Component.literal(e.toString()));
							return 0;
						}

						return showSettings(context);
					}))))
				.then(literal("history").executes(context -> {
					List<OperationCoordinator.Finished> history = EnvironmentRuntime.get().history();
					for (OperationCoordinator.Finished finished : history.subList(Math.max(0, history.size() - HISTORY_LINES), history.size())) {
						context.getSource().sendFeedback(Component.literal(finished.operation().id() + " " + finished.operation().kind()
							+ " ticks " + finished.operation().startTick() + "-" + finished.endTick() + (finished.succeeded() ? " ok" : " failed: " + finished.error())));
					}

					return 1;
				}))
				.then(literal("status").executes(context -> {
					OperationCoordinator.Status status = EnvironmentRuntime.get().status();
					context.getSource().sendFeedback(Component.literal("State " + status.state()
						+ (status.current() == null ? "" : ", running " + status.current().id() + " " + status.current().kind())
						+ (status.lastError() == null ? "" : ", last error: " + status.lastError())));
					return 1;
				})));
		});
	}

	private static int step(CommandContext<FabricClientCommandSource> context, String type, double value) {
		Action action;
		try {
			action = Action.parse(type, value);
		} catch (EnvironmentException e) {
			context.getSource().sendError(Component.literal(e.toString()));
			return 0;
		}

		return report(context, EnvironmentRuntime.get().step(action, STEP_PROJECTION), result -> {
			CameraPose before = result.before();
			CameraPose after = result.after();
			return String.format(Locale.ROOT,
				"Step %d (%s) %s: requested %s, completed %s %s in %d ticks; moved %.2f horizontally and %+.2f up, yaw %.1f, pitch %.1f; "
					+ "auto-jump %s, %d jumps; collided %s, on ground %s; server tick %d",
				result.index(), result.operationId(), action.type(), format(result.requested()), format(result.completed()), action.unit(), result.completedTicks(),
				Math.hypot(after.x() - before.x(), after.z() - before.z()), after.y() - before.y(), after.yawDegrees(), after.pitchDegrees(),
				result.autoJump() ? "on" : "off", result.autoJumps(), result.collided(), result.onGround(), result.serverTick()
			);
		});
	}

	private static String format(double value) {
		return value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.2f", value);
	}

	private static int showSettings(CommandContext<FabricClientCommandSource> context) {
		EnvironmentSettings settings = EnvironmentRuntime.get().settings();
		context.getSource().sendFeedback(Component.literal(String.format(Locale.ROOT,
			"Auto-jump %s; turn within ±%.0f°, look within ±%.0f°, move up to %d ticks, wait up to %d ticks",
			settings.autoJump() ? "on" : "off", settings.limits().maxTurnDegrees(), settings.limits().maxLookDegrees(), settings.limits().maxMoveTicks(), settings.limits().maxWaitTicks()
		)));
		return 1;
	}

	private static int observe(CommandContext<FabricClientCommandSource> context) {
		String label = StringArgumentType.getString(context, "label");
		if (!LABEL.matcher(label).matches()) {
			context.getSource().sendError(Component.literal("Label must match " + LABEL.pattern()));
			return 0;
		}

		int width = IntegerArgumentType.getInteger(context, "width");
		int height = IntegerArgumentType.getInteger(context, "height");
		boolean panorama = context.getNodes().stream().anyMatch(node -> node.getNode().getName().equals("panorama"));
		CaptureProjection projection;
		try {
			projection = panorama ? new PanoramicProjection(width, height, IntegerArgumentType.getInteger(context, "face")) : new PerspectiveProjection(width, height);
		} catch (IllegalArgumentException e) {
			context.getSource().sendError(Component.literal(e.getMessage()));
			return 0;
		}

		Path directory = context.getSource().getClient().gameDirectory.toPath().resolve(DEBUG_DIRECTORY).resolve(label);
		return report(context, EnvironmentRuntime.get().observe(projection), observation -> {
			try {
				CaptureDebugFiles.write(directory, observation.capture().images());
				if (panorama) {
					List<CapturedImages> faces = observation.capture().views();
					for (int i = 0; i < faces.size(); i++) {
						CaptureDebugFiles.write(directory.resolve("faces").resolve(CubeFace.values()[i].name().toLowerCase(Locale.ROOT)), faces.get(i));
					}
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}

			CapturedImages images = observation.capture().images();
			return "Observed (" + observation.operationId() + "): " + images.visibleMask().length + " px, visible "
				+ MaskStatistics.of(images.visibleMask(), images.width(), images.height()).positivePixels() + ", unoccluded " + images.unoccludedPixels() + " -> " + directory;
		});
	}

	/** Builds one scene of a preset or recipe file and moves to its first viewpoint. */
	private static int preview(CommandContext<FabricClientCommandSource> context, boolean fromFile) {
		long seed = has(context, "seed") ? LongArgumentType.getLong(context, "seed") : 0;
		int index = has(context, "index") ? IntegerArgumentType.getInteger(context, "index") : 0;
		String name;
		SceneSpec spec;
		try {
			if (fromFile) {
				name = StringArgumentType.getString(context, "file");
				Path file = context.getSource().getClient().gameDirectory.toPath().resolve(name);
				spec = SceneSpec.read(JsonFields.of(JsonFields.parseObject(Files.readString(file, StandardCharsets.UTF_8))), "level1");
			} else {
				name = StringArgumentType.getString(context, "name");
				spec = ScenePresets.spec(name);
			}
		} catch (IOException | ContractException | IllegalArgumentException e) {
			context.getSource().sendError(Component.literal(e.getMessage()));
			return 0;
		}

		if (spec.base() instanceof SceneBase.NaturalTerrain natural) {
			IntegratedServer server = context.getSource().getClient().getSingleplayerServer();
			if (server == null) {
				context.getSource().sendError(Component.literal("Natural previews need a local world"));
				return 0;
			}

			context.getSource().sendFeedback(Component.literal("Preparing source patch " + ScenePreview.patchIndex(spec, index) + "; new terrain takes a while to generate"));
			NaturalPatches.Request request = new NaturalPatches.Request(seed, natural, ScenePreview.patchIndex(spec, index), ScenePreview.targetBlockId());
			CompletableFuture<?> done = NaturalPatches.prepareAsync(server, request)
				.thenComposeAsync(patch -> showPreview(context, ScenePreview.of(spec, patch, seed, index), name, seed, index), context.getSource().getClient());
			report(context, done, ignored -> "Prepared");
			return 1;
		}

		showPreview(context, ScenePreview.of(spec, seed, index), name, seed, index);
		return 1;
	}

	private static CompletableFuture<?> showPreview(CommandContext<FabricClientCommandSource> context, ScenePreview preview, String name, long seed, int index) {
		if (preview.viewpoint() == null) {
			context.getSource().sendError(Component.literal("Scene " + index + " of " + name + " has no placeable first viewpoint; try another index"));
			return CompletableFuture.completedFuture(null);
		}

		CompletableFuture<EnvironmentRuntime.SceneState> reset = EnvironmentRuntime.get().reset(preview.arena(), preview.scene().plan(), preview.viewpoint().pose(preview.arena()));
		report(context, reset, scene -> "Reset " + name + " seed " + seed + " scene " + index + " (" + scene.operationId() + "): " + preview.scene().layoutParameters());
		return reset;
	}

	private static boolean has(CommandContext<FabricClientCommandSource> context, String argument) {
		return context.getNodes().stream().anyMatch(node -> node.getNode().getName().equals(argument));
	}

	private static int viewpoint(CommandContext<FabricClientCommandSource> context, CameraPose pose) {
		return report(context, EnvironmentRuntime.get().setViewpoint(pose), state -> "Viewpoint " + state.pose() + " (" + state.operationId() + ")");
	}

	private static <T> int report(CommandContext<FabricClientCommandSource> context, CompletableFuture<T> future, Function<T, String> success) {
		FabricClientCommandSource source = context.getSource();
		future.whenComplete((value, error) -> {
			if (error == null) {
				source.sendFeedback(Component.literal(success.apply(value)));
			} else {
				Throwable cause = Futures.cause(error);
				source.sendError(Component.literal(cause instanceof EnvironmentException environment ? environment.toString() : String.valueOf(cause)));
			}
		});
		return 1;
	}
}
