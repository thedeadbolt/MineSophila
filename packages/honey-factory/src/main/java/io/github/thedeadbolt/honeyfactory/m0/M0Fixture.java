package io.github.thedeadbolt.honeyfactory.m0;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Temporary M0 rendering-feasibility fixture: one honey block, a fixed camera pose and
 * optional opaque occluders. The command mutates the world only on the logical server
 * and refuses any save not explicitly named {@link #WORLD_NAME}.
 *
 * <p>M2 replaces the name check with a persistent dataset-ownership record.
 */
public final class M0Fixture {
	public static final String WORLD_NAME = "honey-factory-m0";

	public static final BlockPos TARGET = new BlockPos(0, 200, 0);

	/** Feet position; the eye is {@link #EYE_HEIGHT} above it. */
	public static final Vec3 CAMERA_FEET = new Vec3(-5.0 / 12.0, 200.0, 6.5);

	public static final double EYE_HEIGHT = 1.62;

	private static final BlockPos CLEAR_MIN = new BlockPos(-6, 199, -5);
	private static final BlockPos CLEAR_MAX = new BlockPos(6, 206, 10);
	private static final BlockPos FLOOR_MIN = new BlockPos(-4, 199, -3);
	private static final BlockPos FLOOR_MAX = new BlockPos(4, 199, 8);

	private static final SimpleCommandExceptionType NOT_FIXTURE_WORLD = new SimpleCommandExceptionType(
		Component.literal("M0 fixtures run only in a world named '" + WORLD_NAME + "'")
	);

	/**
	 * Opaque occlusion cases. With the fixed camera, the partial occluder's inner vertical
	 * edge projects through the centre of the honey's front face.
	 */
	public enum Variant {
		OPEN,
		PARTIAL,
		FULL;

		private boolean occludes(int x) {
			return switch (this) {
				case OPEN -> false;
				case PARTIAL -> x == -1;
				case FULL -> x == -1 || x == 0;
			};
		}
	}

	private M0Fixture() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		var fixture = literal("fixture");
		for (Variant variant : Variant.values()) {
			fixture.then(literal(variant.name().toLowerCase())
				.then(argument("player", EntityArgument.player()).executes(context -> build(context, variant))));
		}

		dispatcher.register(literal("honeyfactory")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(literal("m0").then(fixture)));
	}

	public static Vec3 eyePosition() {
		return CAMERA_FEET.add(0.0, EYE_HEIGHT, 0.0);
	}

	/** Yaw that aims the eye at the target centre, in Minecraft degrees. */
	public static float yaw() {
		Vec3 delta = Vec3.atCenterOf(TARGET).subtract(eyePosition());
		return (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
	}

	/** Pitch that aims the eye at the target centre; positive looks down. */
	public static float pitch() {
		Vec3 delta = Vec3.atCenterOf(TARGET).subtract(eyePosition());
		return (float) Math.toDegrees(-Math.atan2(delta.y, delta.horizontalDistance()));
	}

	private static int build(CommandContext<CommandSourceStack> context, Variant variant) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		MinecraftServer server = source.getServer();
		if (!WORLD_NAME.equals(server.getWorldData().getLevelName())) {
			throw NOT_FIXTURE_WORLD.create();
		}

		ServerPlayer player = EntityArgument.getPlayer(context, "player");
		ServerLevel level = server.overworld();
		int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

		for (BlockPos pos : BlockPos.betweenClosed(CLEAR_MIN, CLEAR_MAX)) {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
		}

		for (BlockPos pos : BlockPos.betweenClosed(FLOOR_MIN, FLOOR_MAX)) {
			level.setBlock(pos, Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState(), flags);
		}

		level.setBlock(TARGET, Blocks.HONEY_BLOCK.defaultBlockState(), flags);
		BlockState stone = Blocks.STONE.defaultBlockState();
		for (int x = -1; x <= 0; x++) {
			if (variant.occludes(x)) {
				for (int y = 200; y <= 201; y++) {
					level.setBlock(new BlockPos(x, y, 3), stone, flags);
				}
			}
		}

		server.tickRateManager().setFrozen(true);
		player.teleportTo(level, CAMERA_FEET.x, CAMERA_FEET.y, CAMERA_FEET.z, Set.of(), yaw(), pitch(), true);
		player.setDeltaMovement(Vec3.ZERO);
		source.sendSuccess(() -> Component.literal("Built M0 fixture " + variant.name().toLowerCase()), false);
		return 1;
	}
}
