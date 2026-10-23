package io.github.thedeadbolt.honeyfactory.scene;

import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.Algorithms;
import io.github.thedeadbolt.honeyfactory.contract.SceneSeeds;
import java.util.ArrayList;
import java.util.List;

/**
 * Level 1, open platform ({@value Algorithms#LEVEL1_GENERATOR}): one neutral concrete floor
 * over the whole arena footprint and, in target-present scenes, one target block standing
 * on it. Nothing else is placed.
 *
 * <ul>
 *   <li>Target cell, from the target seed: uniform over the footprint, on the floor. The
 *       allowed block state is drawn uniformly from the configured list. Target-absent
 *       scenes draw the same cell as the aim point and leave it empty.</li>
 *   <li>Viewpoint {@code v}, draw {@code a}, from the viewpoint seed: horizontal distance
 *       uniform in the configured range, azimuth uniform in [0, 360) degrees around the
 *       aim block's centre, feet standing on the floor. The camera aims at the block centre;
 *       uniform yaw and pitch jitter follow, and both angles are rounded to the float
 *       precision Minecraft stores.</li>
 *   <li>A draw is rejected when the player would not stand fully on the floor or would
 *       overlap the aim block.</li>
 * </ul>
 */
public final class Level1Generator implements SceneGenerator {
	public static final String FLOOR = "minecraft:light_gray_concrete";
	public static final String TEMPLATE_ID = "level1-platform";

	/** Half the player's collision width, in blocks. */
	private static final double PLAYER_HALF_WIDTH = 0.3;
	private static final double PLAYER_HEIGHT = 1.8;
	private static final String PREFIX = "honey-factory/" + Algorithms.LEVEL1_GENERATOR + "/";

	private final ResolvedConfig config;
	private final Template template;

	public Level1Generator(ResolvedConfig config) {
		if (config.difficulty() != 1) {
			throw new IllegalArgumentException("Level1Generator needs a level-1 configuration, got level " + config.difficulty());
		}

		this.config = config;
		ResolvedConfig.Arena arena = config.arena();
		this.template = Template.platform(TEMPLATE_ID, 1, arena.width(), arena.length(), arena.height(), FLOOR);
	}

	@Override
	public String version() {
		return Algorithms.LEVEL1_GENERATOR;
	}

	public Template template() {
		return this.template;
	}

	@Override
	public GeneratedScene scene(SceneSeeds seeds, boolean targetPresent) {
		SeededRandom random = new SeededRandom(PREFIX + "target/" + seeds.target());
		int x = random.nextInt(this.config.arena().width());
		int z = random.nextInt(this.config.arena().length());
		List<String> states = this.config.target().allowedBlockStates();
		String state = states.get(random.nextInt(states.size()));
		List<Placement> delta = new ArrayList<>();
		Placement target = null;
		if (targetPresent) {
			target = new Placement(x, 1, z, state);
			delta.add(target);
		}

		ResolvedConfig.Lighting lighting = this.config.lighting();
		ScenePlan plan = new ScenePlan(this.template, delta, target, lighting.timeOfDayTicks(), lighting.weather());
		return new GeneratedScene(seeds, plan, new int[] {x, 1, z});
	}

	@Override
	public ViewpointCandidate viewpoint(GeneratedScene scene, int viewpointIndex, int attemptIndex) {
		ResolvedConfig.Viewpoints settings = this.config.viewpoints();
		SeededRandom random = new SeededRandom(PREFIX + "viewpoint/" + scene.seeds().viewpoint() + "/" + viewpointIndex + "/" + attemptIndex);
		double distance = random.uniform(settings.distanceMinBlocks(), settings.distanceMaxBlocks());
		double azimuth = Math.toRadians(random.uniform(0.0, 360.0));
		double yawJitter = random.uniform(-settings.yawJitterDegrees(), settings.yawJitterDegrees());
		double pitchJitter = random.uniform(-settings.pitchJitterDegrees(), settings.pitchJitterDegrees());

		int[] aim = scene.aimBlock();
		double x = aim[0] + 0.5 + distance * Math.sin(azimuth);
		double z = aim[2] + 0.5 + distance * Math.cos(azimuth);
		double feetY = 1.0;
		ResolvedConfig.Arena arena = this.config.arena();
		if (x < PLAYER_HALF_WIDTH || x > arena.width() - PLAYER_HALF_WIDTH || z < PLAYER_HALF_WIDTH || z > arena.length() - PLAYER_HALF_WIDTH) {
			return ViewpointCandidate.rejected("camera_off_platform");
		}

		boolean overlapsAim = x + PLAYER_HALF_WIDTH > aim[0] && x - PLAYER_HALF_WIDTH < aim[0] + 1
			&& z + PLAYER_HALF_WIDTH > aim[2] && z - PLAYER_HALF_WIDTH < aim[2] + 1
			&& feetY < aim[1] + 1 && feetY + PLAYER_HEIGHT > aim[1];
		if (overlapsAim) {
			return ViewpointCandidate.rejected("camera_overlaps_target");
		}

		ViewpointPlan base = ViewpointPlan.lookingAt(x, feetY, z, settings.eyeHeightBlocks(), aim[0], aim[1], aim[2]);
		float yaw = (float) ViewpointPlan.wrapDegrees(base.yawDegrees() + yawJitter);
		float pitch = (float) Math.clamp(base.pitchDegrees() + pitchJitter, -90.0, 90.0);
		return ViewpointCandidate.of(new ViewpointPlan(x, feetY, z, yaw, pitch));
	}
}
