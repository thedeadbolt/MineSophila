package io.github.thedeadbolt.honeyfactory.scene;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.Algorithms;
import io.github.thedeadbolt.honeyfactory.contract.SceneSeeds;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds scenes from a {@link SceneSpec} ({@value Algorithms#SCENE_RECIPE}) on a platform
 * base: a floor over the arena footprint, one target cell standing on it, the recipe's
 * parts around it, and viewpoints standing on the floor aimed at the target cell.
 *
 * <ul>
 *   <li>Target cell, from the target seed: uniform over the footprint, on the floor. The
 *       allowed block state is drawn uniformly from the configured list. Target-absent
 *       scenes draw the same cell as the aim point and leave it empty; the layout around
 *       it is the same in both classes.</li>
 *   <li>Parts, in order, into one shared {@link LayoutBuilder}. Part {@code i} of type
 *       {@code t} draws from its own stream {@code part/<i>/<t>/<layout seed>}, so editing
 *       one part never shifts another's draws. Its draws are recorded under
 *       {@code layout.parts[i]}.</li>
 *   <li>Lighting, from {@code lighting/<layout seed>}: see {@link Lighting#draw}.</li>
 *   <li>Viewpoint {@code v}, draw {@code a}, from {@code viewpoint/<viewpoint seed>/<v>/<a>}:
 *       horizontal distance uniform in the configured range, azimuth uniform in [0, 360)
 *       degrees around the aim block's centre, feet standing on the floor. The camera aims
 *       at the block centre; uniform yaw and pitch jitter follow, and both angles are
 *       rounded to the float precision Minecraft stores. With aim {@code image_uniform} the
 *       draw instead places the block centre at a uniform output pixel; see
 *       {@link ViewpointAims}.</li>
 *   <li>A draw is rejected when the player would not stand fully on the floor, would
 *       overlap the aim block or another block, or when the recipe's {@link SightRule}
 *       rejects the eye's view of the aim cell.</li>
 * </ul>
 *
 * <p>Every stream is keyed {@code honey-factory/scene-recipe-v1/<purpose>}.
 */
public final class RecipeGenerator implements SceneGenerator {
	public static final String TEMPLATE_ID = "platform";
	public static final int TEMPLATE_VERSION = 1;
	public static final int GROUND_Y = LayoutBuilder.GROUND_Y;

	/** Half the player's collision width, in blocks. */
	private static final double PLAYER_HALF_WIDTH = 0.3;
	private static final double PLAYER_HEIGHT = 1.8;
	private static final String PREFIX = "honey-factory/" + Algorithms.SCENE_RECIPE + "/";

	private final SceneSpec spec;
	private final ResolvedConfig.Arena arena;
	private final List<String> targetStates;
	private final ResolvedConfig.Viewpoints viewpoints;
	private final CaptureProjection projection;
	private final Template template;

	public RecipeGenerator(ResolvedConfig config) {
		this(config.scene(), config.arena(), config.target().allowedBlockStates(), config.viewpoints(), config.projection());
	}

	public RecipeGenerator(SceneSpec spec, ResolvedConfig.Arena arena, List<String> targetStates, ResolvedConfig.Viewpoints viewpoints, CaptureProjection projection) {
		if (!(spec.base() instanceof SceneBase.Platform platform)) {
			throw new IllegalArgumentException("A platform generator needs a platform base, got " + spec.base());
		}

		this.spec = spec;
		this.arena = arena;
		this.targetStates = List.copyOf(targetStates);
		this.viewpoints = viewpoints;
		this.projection = projection;
		this.template = Template.platform(TEMPLATE_ID, TEMPLATE_VERSION, arena.width(), arena.length(), arena.height(), platform.floor());
	}

	@Override
	public String version() {
		return Algorithms.SCENE_RECIPE;
	}

	public SceneSpec spec() {
		return this.spec;
	}

	public Template template() {
		return this.template;
	}

	@Override
	public GeneratedScene scene(SceneSeeds seeds, boolean targetPresent) {
		SeededRandom random = random("target/" + seeds.target());
		int x = random.nextInt(this.arena.width());
		int z = random.nextInt(this.arena.length());
		String state = this.targetStates.get(random.nextInt(this.targetStates.size()));
		int[] aim = {x, GROUND_Y, z};
		LayoutBuilder layout = new LayoutBuilder(this.arena.width(), this.arena.length(), this.arena.height(), aim);
		JsonArray drawnParts = new JsonArray();
		for (int i = 0; i < this.spec.parts().size(); i++) {
			LayoutPart part = this.spec.parts().get(i);
			JsonObject drawn = new JsonObject();
			drawn.addProperty("type", part.type());
			part.build(new LayoutPart.Context(layout, aim, random("part/" + i + "/" + part.type() + "/" + seeds.layout()), drawn));
			drawnParts.add(drawn);
		}

		List<Placement> delta = new ArrayList<>(layout.placements());
		JsonObject parameters = new JsonObject();
		parameters.addProperty("preset", this.spec.preset());
		parameters.add("parts", drawnParts);
		parameters.addProperty("blocks", delta.size());
		Placement target = null;
		if (targetPresent) {
			target = new Placement(x, GROUND_Y, z, state);
			delta.add(target);
		}

		Lighting.Drawn lighting = this.spec.lighting().draw(random("lighting/" + seeds.layout()));
		ScenePlan plan = new ScenePlan(this.template, delta, target, lighting.timeOfDayTicks(), lighting.weather());
		return new GeneratedScene(seeds, plan, aim, parameters);
	}

	@Override
	public ViewpointCandidate viewpoint(GeneratedScene scene, int viewpointIndex, int attemptIndex) {
		ResolvedConfig.Viewpoints settings = this.viewpoints;
		SeededRandom random = random("viewpoint/" + scene.seeds().viewpoint() + "/" + viewpointIndex + "/" + attemptIndex);
		double distance = random.uniform(settings.distanceMinBlocks(), settings.distanceMaxBlocks());
		double azimuth = Math.toRadians(random.uniform(0.0, 360.0));
		double yawJitter = random.uniform(-settings.yawJitterDegrees(), settings.yawJitterDegrees());
		double pitchJitter = random.uniform(-settings.pitchJitterDegrees(), settings.pitchJitterDegrees());

		int[] aim = scene.aimBlock();
		double x = aim[0] + 0.5 + distance * Math.sin(azimuth);
		double z = aim[2] + 0.5 + distance * Math.cos(azimuth);
		double feetY = GROUND_Y;
		if (x < PLAYER_HALF_WIDTH || x > this.arena.width() - PLAYER_HALF_WIDTH || z < PLAYER_HALF_WIDTH || z > this.arena.length() - PLAYER_HALF_WIDTH) {
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

		String sight = this.spec.sight().check(grid, new double[] {x, feetY + settings.eyeHeightBlocks(), z}, aim);
		if (sight != null) {
			return new ViewpointCandidate.Rejected(sight);
		}

		return ViewpointAims.orient(settings, this.projection, random, x, feetY, z, aim, yawJitter, pitchJitter);
	}

	private static SeededRandom random(String purpose) {
		return new SeededRandom(PREFIX + purpose);
	}
}
