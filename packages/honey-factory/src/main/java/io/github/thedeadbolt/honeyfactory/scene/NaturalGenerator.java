package io.github.thedeadbolt.honeyfactory.scene;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.Algorithms;
import io.github.thedeadbolt.honeyfactory.contract.SceneSeeds;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds scenes on a {@link SceneBase.NaturalTerrain natural} base ({@value Algorithms#SCENE_RECIPE}):
 * the source patch copied unchanged, and in target-present scenes one target placed on its
 * surface. Natural bases take no layout parts. Coordinates are relative to the core's
 * minimum corner at the bottom of the copied window.
 *
 * <ul>
 *   <li>Target, from the target seed: a site drawn uniformly from the core's
 *       {@link PatchTerrain#targetSites() sites}, then a block state from the allowed list.
 *       A target-absent scene aims at the same site and leaves it as the source has it.</li>
 *   <li>Lighting, from {@code lighting/<layout seed>}: see {@link Lighting#draw}.</li>
 *   <li>Viewpoint draws as on a platform (distance, azimuth, jitter), the feet on the
 *       surface of their column. A draw is rejected when the camera leaves the core, stands
 *       in or on fluid or on a face that cannot hold it, overlaps a colliding block or the
 *       aim cell, or fails the recipe's {@link SightRule}. On terrain only full opaque cubes
 *       block an estimated sight line, so the estimate errs towards keeping views; the mask
 *       decides.</li>
 * </ul>
 */
public final class NaturalGenerator implements SceneGenerator {
	public static final String TEMPLATE_ID = "natural-patch";
	public static final int TEMPLATE_VERSION = 1;
	private static final double PLAYER_HALF_WIDTH = 0.3;
	private static final double PLAYER_HEIGHT = 1.8;
	private static final String PREFIX = "honey-factory/" + Algorithms.SCENE_RECIPE + "/";

	private final SceneSpec spec;
	private final SceneBase.NaturalTerrain base;
	private final List<String> targetStates;
	private final ResolvedConfig.Viewpoints viewpoints;
	private final Template template;

	public NaturalGenerator(ResolvedConfig config) {
		this(config.scene(), config.target().allowedBlockStates(), config.viewpoints());
	}

	public NaturalGenerator(SceneSpec spec, List<String> targetStates, ResolvedConfig.Viewpoints viewpoints) {
		if (!(spec.base() instanceof SceneBase.NaturalTerrain natural)) {
			throw new IllegalArgumentException("A natural generator needs a natural_patch base, got " + spec.base());
		}

		this.spec = spec;
		this.base = natural;
		this.targetStates = List.copyOf(targetStates);
		this.viewpoints = viewpoints;
		this.template = new Template(TEMPLATE_ID, TEMPLATE_VERSION, natural.coreWidth(), natural.coreLength(), natural.height(), List.of());
	}

	@Override
	public String version() {
		return Algorithms.SCENE_RECIPE;
	}

	public SceneSpec spec() {
		return this.spec;
	}

	public SceneBase.NaturalTerrain base() {
		return this.base;
	}

	/** @throws UnsupportedOperationException always; natural scenes need a prepared source patch */
	@Override
	public GeneratedScene scene(SceneSeeds seeds, boolean targetPresent) {
		throw new UnsupportedOperationException("Scenes on a natural base need a prepared source patch");
	}

	/** @throws IllegalArgumentException when the patch's core has no target site */
	public GeneratedScene scene(SceneSeeds seeds, boolean targetPresent, PreparedPatch prepared) {
		PatchTerrain terrain = prepared.terrain();
		if (terrain.width() != this.base.coreWidth() || terrain.length() != this.base.coreLength() || terrain.height() != this.base.height()) {
			throw new IllegalArgumentException("The patch's terrain does not match the base's core");
		}

		List<int[]> sites = terrain.targetSites();
		if (sites.isEmpty()) {
			throw new IllegalArgumentException("Source patch " + prepared.patch().patchId() + " has no target site");
		}

		SeededRandom random = random("target/" + seeds.target());
		int[] aim = sites.get(random.nextInt(sites.size()));
		String state = this.targetStates.get(random.nextInt(this.targetStates.size()));
		List<Placement> delta = new ArrayList<>();
		Placement target = null;
		if (targetPresent) {
			target = new Placement(aim[0], aim[1], aim[2], state);
			delta.add(target);
		}

		SourcePatch patch = prepared.patch();
		JsonObject patchParameters = new JsonObject();
		patchParameters.addProperty("index", patch.patchIndex());
		patchParameters.addProperty("draw", patch.draw());
		patchParameters.addProperty("patch_id", patch.patchId());
		JsonObject rejected = new JsonObject();
		patch.rejectedDraws().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> rejected.addProperty(entry.getKey(), entry.getValue()));
		patchParameters.add("rejected_draws", rejected);
		JsonObject parameters = new JsonObject();
		parameters.addProperty("preset", this.spec.preset());
		parameters.add("patch", patchParameters);
		parameters.addProperty("target_sites", sites.size());
		parameters.add("parts", new JsonArray());
		parameters.addProperty("blocks", 0);
		Lighting.Drawn lighting = this.spec.lighting().draw(random("lighting/" + seeds.layout()));
		ScenePlan plan = new ScenePlan(this.template, delta, target, lighting.timeOfDayTicks(), lighting.weather(), patch);
		return new GeneratedScene(seeds, plan, aim.clone(), parameters, terrain);
	}

	@Override
	public ViewpointCandidate viewpoint(GeneratedScene scene, int viewpointIndex, int attemptIndex) {
		PatchTerrain terrain = scene.terrain();
		if (terrain == null) {
			throw new IllegalArgumentException("A natural scene carries its patch's terrain");
		}

		ResolvedConfig.Viewpoints settings = this.viewpoints;
		SeededRandom random = random("viewpoint/" + scene.seeds().viewpoint() + "/" + viewpointIndex + "/" + attemptIndex);
		double distance = random.uniform(settings.distanceMinBlocks(), settings.distanceMaxBlocks());
		double azimuth = Math.toRadians(random.uniform(0.0, 360.0));
		double yawJitter = random.uniform(-settings.yawJitterDegrees(), settings.yawJitterDegrees());
		double pitchJitter = random.uniform(-settings.pitchJitterDegrees(), settings.pitchJitterDegrees());

		int[] aim = scene.aimBlock();
		double x = aim[0] + 0.5 + distance * Math.sin(azimuth);
		double z = aim[2] + 0.5 + distance * Math.cos(azimuth);
		if (x < PLAYER_HALF_WIDTH || x > terrain.width() - PLAYER_HALF_WIDTH || z < PLAYER_HALF_WIDTH || z > terrain.length() - PLAYER_HALF_WIDTH) {
			return new ViewpointCandidate.Rejected("camera_outside_core");
		}

		int column = (int) Math.floor(x);
		int row = (int) Math.floor(z);
		int feet = terrain.surface(column, row);
		double eyeY = feet + settings.eyeHeightBlocks();
		if (feet < 1 || feet + PLAYER_HEIGHT > terrain.height()) {
			return new ViewpointCandidate.Rejected("camera_outside_region");
		}

		if (terrain.has(column, feet - 1, row, PatchTerrain.FLUID) || terrain.has(column, (int) Math.floor(eyeY), row, PatchTerrain.FLUID)) {
			return new ViewpointCandidate.Rejected("camera_in_water");
		}

		if (!terrain.has(column, feet - 1, row, PatchTerrain.STURDY_TOP)) {
			return new ViewpointCandidate.Rejected("camera_unsupported");
		}

		boolean overlapsAim = x + PLAYER_HALF_WIDTH > aim[0] && x - PLAYER_HALF_WIDTH < aim[0] + 1
			&& z + PLAYER_HALF_WIDTH > aim[2] && z - PLAYER_HALF_WIDTH < aim[2] + 1
			&& feet < aim[1] + 1 && feet + PLAYER_HEIGHT > aim[1];
		if (overlapsAim) {
			return new ViewpointCandidate.Rejected("camera_overlaps_target");
		}

		for (int y = feet; y < feet + PLAYER_HEIGHT; y++) {
			for (int cz = (int) Math.floor(z - PLAYER_HALF_WIDTH); cz < z + PLAYER_HALF_WIDTH; cz++) {
				for (int cx = (int) Math.floor(x - PLAYER_HALF_WIDTH); cx < x + PLAYER_HALF_WIDTH; cx++) {
					if (terrain.has(cx, y, cz, PatchTerrain.COLLIDES)) {
						return new ViewpointCandidate.Rejected("camera_inside_block");
					}
				}
			}
		}

		String sight = this.spec.sight().check(terrain, new double[] {x, eyeY, z}, aim);
		if (sight != null) {
			return new ViewpointCandidate.Rejected(sight);
		}

		ViewpointPlan base = ViewpointPlan.lookingAt(x, feet, z, settings.eyeHeightBlocks(), aim[0], aim[1], aim[2]);
		float yaw = (float) ViewpointPlan.wrapDegrees(base.yawDegrees() + yawJitter);
		float pitch = (float) Math.clamp(base.pitchDegrees() + pitchJitter, -90.0, 90.0);
		return new ViewpointCandidate.Placeable(new ViewpointPlan(x, feet, z, yaw, pitch));
	}

	private static SeededRandom random(String purpose) {
		return new SeededRandom(PREFIX + purpose);
	}
}
