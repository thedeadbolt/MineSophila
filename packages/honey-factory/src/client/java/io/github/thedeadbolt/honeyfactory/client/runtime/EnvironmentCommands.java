package io.github.thedeadbolt.honeyfactory.client.runtime;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.thedeadbolt.honeyfactory.capture.CaptureDebugFiles;
import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.capture.PerspectiveProjection;
import io.github.thedeadbolt.honeyfactory.contract.MaskStatistics;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import io.github.thedeadbolt.honeyfactory.environment.OperationCoordinator;
import io.github.thedeadbolt.honeyfactory.scene.FixturePlans;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * {@code /honeyfactory_env}: debug commands that call {@link EnvironmentRuntime}; they
 * contain no environment logic of their own.
 *
 * <pre>
 * reset &lt;fixture&gt;                          build a fixture plan and move to its viewpoint
 * viewpoint fixture | &lt;x y z yaw pitch&gt;      place the camera (feet position)
 * observe &lt;label&gt; &lt;width&gt; &lt;height&gt;          capture RGB and masks into honey-factory-debug/&lt;label&gt;
 * step &lt;action&gt;                             rejected until M7
 * status
 * </pre>
 */
public final class EnvironmentCommands {
	public static final String DEBUG_DIRECTORY = "honey-factory-debug";

	private static final Pattern LABEL = Pattern.compile("[a-z0-9_-]{1,64}");

	private EnvironmentCommands() {
	}

	public static void initialize() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			LiteralArgumentBuilder<FabricClientCommandSource> reset = literal("reset");
			for (FixturePlans.Fixture fixture : FixturePlans.Fixture.values()) {
				reset.then(literal(fixture.commandName()).executes(context -> report(context, EnvironmentRuntime.get().reset(
					FixturePlans.ARENA, FixturePlans.plan(fixture), EnvironmentRuntime.pose(FixturePlans.viewpoint(fixture))
				), scene -> "Reset " + fixture.commandName() + " (" + scene.operationId() + "), layout " + scene.layoutSha256())));
			}

			dispatcher.register(literal("honeyfactory_env")
				.then(reset)
				.then(literal("viewpoint")
					.then(literal("fixture").executes(context -> viewpoint(context, EnvironmentRuntime.pose(FixturePlans.VIEWPOINT))))
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
					.then(argument("height", IntegerArgumentType.integer(PerspectiveProjection.MIN_SIZE, 4096)).executes(EnvironmentCommands::observe)))))
				.then(literal("step").then(argument("action", StringArgumentType.word())
					.executes(context -> report(context, EnvironmentRuntime.get().step(StringArgumentType.getString(context, "action")), ignored -> "Stepped"))))
				.then(literal("status").executes(context -> {
					OperationCoordinator.Status status = EnvironmentRuntime.get().status();
					context.getSource().sendFeedback(Component.literal("State " + status.state()
						+ (status.current() == null ? "" : ", running " + status.current().id() + " " + status.current().kind())
						+ (status.lastError() == null ? "" : ", last error: " + status.lastError())));
					return 1;
				})));
		});
	}

	private static int observe(CommandContext<FabricClientCommandSource> context) {
		String label = StringArgumentType.getString(context, "label");
		if (!LABEL.matcher(label).matches()) {
			context.getSource().sendError(Component.literal("Label must match " + LABEL.pattern()));
			return 0;
		}

		PerspectiveProjection projection = new PerspectiveProjection(IntegerArgumentType.getInteger(context, "width"), IntegerArgumentType.getInteger(context, "height"));
		Path directory = context.getSource().getClient().gameDirectory.toPath().resolve(DEBUG_DIRECTORY).resolve(label);
		return report(context, EnvironmentRuntime.get().observe(projection), observation -> {
			try {
				CaptureDebugFiles.write(directory, observation.capture().images());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}

			CapturedImages images = observation.capture().images();
			return "Observed (" + observation.operationId() + "): " + images.visibleMask().length + " px, visible "
				+ MaskStatistics.of(images.visibleMask(), images.width(), images.height()).positivePixels() + ", unoccluded " + images.unoccludedPixels() + " -> " + directory;
		});
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
				Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
				source.sendError(Component.literal(cause instanceof EnvironmentException environment ? environment.toString() : String.valueOf(cause)));
			}
		});
		return 1;
	}
}
