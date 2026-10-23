package io.github.thedeadbolt.honeyfactory.scene;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.SceneSeeds;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Shared base of the platform levels 1–4: a neutral concrete floor over the arena
 * footprint, one target cell standing on it, level-specific blocks around it, and
 * viewpoints standing on the floor and aimed at the target cell.
 *
 * <ul>
 *   <li>Target cell, from the target seed: uniform over the footprint, on the floor. The
 *       allowed block state is drawn uniformly from the configured list. Target-absent
 *       scenes draw the same cell as the aim point and leave it empty; the layout around
 *       it is the same in both classes.</li>
 *   <li>Layout, from the layout seed: the level's {@link #layout} blocks, placed before
 *       the target. They never fill the aim cell.</li>
 *   <li>Viewpoint {@code v}, draw {@code a}, from the viewpoint seed: horizontal distance
 *       uniform in the configured range, azimuth uniform in [0, 360) degrees around the
 *       aim block's centre, feet standing on the floor. The camera aims at the block centre;
 *       uniform yaw and pitch jitter follow, and both angles are rounded to the float
 *       precision Minecraft stores.</li>
 *   <li>A draw is rejected when the player would not stand fully on the floor, would
 *       overlap the aim block or another block, or when the level's {@link #checkSight}
 *       rejects the eye's view of the aim cell.</li>
 * </ul>
 *
 * <p>Every stream is keyed by the generator's version, so levels never share draws.
 */
public abstract class PlatformGenerator implements SceneGenerator {
	/** The floor template, named for the level that introduced it; levels 1–4 share it. */
	public static final String TEMPLATE_ID = "level1-platform";
	public static final int TEMPLATE_VERSION = 1;
	public static final String FLOOR = "minecraft:light_gray_concrete";
	/** The floor's top face; the target and the player's feet stand on it. */
	public static final int GROUND_Y = 1;

	/** Half the player's collision width, in blocks. */
	private static final double PLAYER_HALF_WIDTH = 0.3;
	private static final double PLAYER_HEIGHT = 1.8;

	protected final ResolvedConfig config;
	private final String version;
	private final String prefix;
	private final Template template;

	protected PlatformGenerator(ResolvedConfig config, int level, String version) {
		if (config.difficulty() != level) {
			throw new IllegalArgumentException(getClass().getSimpleName() + " needs a level-" + level + " configuration, got level " + config.difficulty());
		}

		this.config = config;
		this.version = version;
		this.prefix = "honey-factory/" + version + "/";
		ResolvedConfig.Arena arena = config.arena();
		this.template = Template.platform(TEMPLATE_ID, TEMPLATE_VERSION, arena.width(), arena.length(), arena.height(), FLOOR);
	}

	@Override
	public final String version() {
		return this.version;
	}

	public final Template template() {
		return this.template;
	}

	@Override
	public final GeneratedScene scene(SceneSeeds seeds, boolean targetPresent) {
		SeededRandom random = random("target/" + seeds.target());
		int x = random.nextInt(this.config.arena().width());
		int z = random.nextInt(this.config.arena().length());
		List<String> states = this.config.target().allowedBlockStates();
		String state = states.get(random.nextInt(states.size()));
		int[] aim = {x, GROUND_Y, z};
		JsonObject parameters = new JsonObject();
		List<Placement> delta = new ArrayList<>(layout(random("layout/" + seeds.layout()), aim, parameters));
		for (Placement placement : delta) {
			if (placement.x() == x && placement.y() == GROUND_Y && placement.z() == z) {
				throw new IllegalStateException(this.version + " placed a block in the aim cell");
			}
		}

		Placement target = null;
		if (targetPresent) {
			target = new Placement(x, GROUND_Y, z, state);
			delta.add(target);
		}

		ResolvedConfig.Lighting lighting = this.config.lighting();
		ScenePlan plan = new ScenePlan(this.template, delta, target, lighting.timeOfDayTicks(), lighting.weather());
		return new GeneratedScene(seeds, plan, aim, parameters);
	}

	/**
	 * The level's blocks around the aim cell, in placement order, drawn from {@code random};
	 * a later placement replaces an earlier one at the same cell. Records what was drawn
	 * in {@code parameters}.
	 */
	protected abstract List<Placement> layout(SeededRandom random, int[] aim, JsonObject parameters);

	/** Null when the eye's view of the aim cell suits the level, otherwise the rejection reason. */
	protected abstract @Nullable String checkSight(SolidGrid grid, double[] eye, int[] aim);

	@Override
	public final ViewpointCandidate viewpoint(GeneratedScene scene, int viewpointIndex, int attemptIndex) {
		ResolvedConfig.Viewpoints settings = this.config.viewpoints();
		SeededRandom random = random("viewpoint/" + scene.seeds().viewpoint() + "/" + viewpointIndex + "/" + attemptIndex);
		double distance = random.uniform(settings.distanceMinBlocks(), settings.distanceMaxBlocks());
		double azimuth = Math.toRadians(random.uniform(0.0, 360.0));
		double yawJitter = random.uniform(-settings.yawJitterDegrees(), settings.yawJitterDegrees());
		double pitchJitter = random.uniform(-settings.pitchJitterDegrees(), settings.pitchJitterDegrees());

		int[] aim = scene.aimBlock();
		double x = aim[0] + 0.5 + distance * Math.sin(azimuth);
		double z = aim[2] + 0.5 + distance * Math.cos(azimuth);
		double feetY = GROUND_Y;
		ResolvedConfig.Arena arena = this.config.arena();
		if (x < PLAYER_HALF_WIDTH || x > arena.width() - PLAYER_HALF_WIDTH || z < PLAYER_HALF_WIDTH || z > arena.length() - PLAYER_HALF_WIDTH) {
			return new ViewpointCandidate.Rejected("camera_off_platform");
		}

		boolean overlapsAim = x + PLAYER_HALF_WIDTH > aim[0] && x - PLAYER_HALF_WIDTH < aim[0] + 1
			&& z + PLAYER_HALF_WIDTH > aim[2] && z - PLAYER_HALF_WIDTH < aim[2] + 1
			&& feetY < aim[1] + 1 && feetY + PLAYER_HEIGHT > aim[1];
		if (overlapsAim) {
			return new ViewpointCandidate.Rejected("camera_overlaps_target");
		}

		SolidGrid grid = SolidGrid.of(scene.plan());
		double[] playerMin = {x - PLAYER_HALF_WIDTH, feetY, z - PLAYER_HALF_WIDTH};
		double[] playerMax = {x + PLAYER_HALF_WIDTH, feetY + PLAYER_HEIGHT, z + PLAYER_HALF_WIDTH};
		if (grid.intersects(playerMin, playerMax)) {
			return new ViewpointCandidate.Rejected("camera_inside_block");
		}

		String sight = checkSight(grid, new double[] {x, feetY + settings.eyeHeightBlocks(), z}, aim);
		if (sight != null) {
			return new ViewpointCandidate.Rejected(sight);
		}

		ViewpointPlan base = ViewpointPlan.lookingAt(x, feetY, z, settings.eyeHeightBlocks(), aim[0], aim[1], aim[2]);
		float yaw = (float) ViewpointPlan.wrapDegrees(base.yawDegrees() + yawJitter);
		float pitch = (float) Math.clamp(base.pitchDegrees() + pitchJitter, -90.0, 90.0);
		return new ViewpointCandidate.Placeable(new ViewpointPlan(x, feetY, z, yaw, pitch));
	}

	/** A stream keyed by this generator's version and {@code purpose}. */
	protected final SeededRandom random(String purpose) {
		return new SeededRandom(this.prefix + purpose);
	}
}
