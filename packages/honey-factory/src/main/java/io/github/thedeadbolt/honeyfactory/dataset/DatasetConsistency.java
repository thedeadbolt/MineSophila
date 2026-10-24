package io.github.thedeadbolt.honeyfactory.dataset;

import io.github.thedeadbolt.honeyfactory.contract.Algorithms;
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import io.github.thedeadbolt.honeyfactory.contract.SampleRecord;
import io.github.thedeadbolt.honeyfactory.contract.SceneRecord;
import io.github.thedeadbolt.honeyfactory.contract.SceneSeeds;
import io.github.thedeadbolt.honeyfactory.contract.Split;
import io.github.thedeadbolt.honeyfactory.contract.SplitGrouping;
import io.github.thedeadbolt.honeyfactory.scene.SceneBase;
import io.github.thedeadbolt.honeyfactory.scene.SceneSpec;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cross-record rules over a manifest and its parsed scene and sample records. Each record
 * is assumed individually valid; this checks what no single record can show.
 *
 * <p>Every dataset, whatever its lifecycle, must be internally consistent: unique IDs,
 * samples inheriting their scene's split and target presence, target-present samples
 * meeting the level's visibility rule, every scene in the class and split its slot
 * algorithm ({@code scene-slots-v1} or {@code patch-slots-v1}) assigns its index, and on a
 * natural base, source patches kept apart across splits. Since a scene's samples inherit
 * its split and each index has exactly one slot, no grouping key can reach two splits.
 * Complete datasets must also meet their quotas and final split counts.
 */
public final class DatasetConsistency {
	private DatasetConsistency() {
	}

	/** Returns every violation found; an empty list means the records are consistent. */
	public static List<String> check(DatasetManifest manifest, List<SceneRecord> scenes, List<SampleRecord> samples) {
		List<String> errors = new ArrayList<>();
		SceneBase sceneBase = SceneSpec.read(JsonFields.of(manifest.sceneRecipe()), "level1").base();
		SceneBase.NaturalTerrain natural = sceneBase instanceof SceneBase.NaturalTerrain terrain ? terrain : null;
		List<SceneSlots.Slot> slots = switch (manifest.splitGrouping()) {
			case SCENE -> SceneSlots.plan(manifest.seed(), manifest.acceptedScenesRequested(), manifest.targetPresentScenes(), manifest.splitRatios());
			case SOURCE_REGION -> natural == null ? null
				: SceneSlots.planPatches(manifest.seed(), manifest.acceptedScenesRequested(), manifest.targetPresentScenes(), natural.scenesPerPatch(), manifest.splitRatios());
			case SOURCE_WORLD -> null;
		};
		DatasetManifest.Visibility visibility = manifest.visibility();
		Map<String, SceneRecord> scenesById = new HashMap<>();
		for (SceneRecord scene : scenes) {
			if (scenesById.put(scene.sceneId(), scene) != null) {
				errors.add("scene " + scene.sceneId() + ": duplicate scene_id");
			}

			if (scene.difficulty() != manifest.difficulty()) {
				errors.add("scene " + scene.sceneId() + ": difficulty " + scene.difficulty() + " differs from the dataset's " + manifest.difficulty());
			}

			if (!scene.target().blockId().equals(manifest.targetBlockId())) {
				errors.add("scene " + scene.sceneId() + ": target block " + scene.target().blockId() + " differs from the dataset's " + manifest.targetBlockId());
			}

			SceneSeeds expected = SceneSeeds.derive(manifest.seed(), scene.generation().sceneIndex(), scene.generation().attemptIndex());
			if (!expected.equals(scene.generation().seeds())) {
				errors.add("scene " + scene.sceneId() + ": seeds do not derive from the dataset seed, scene index and attempt index");
			}

			if (slots != null) {
				checkSlot(scene, slots, Algorithms.slotsFor(manifest.splitGrouping()), errors);
			}

			if ((natural != null) != (scene.sourcePatch() != null)) {
				errors.add("scene " + scene.sceneId() + ": " + (natural != null ? "lacks the source_patch a natural_patch base needs" : "has a source_patch but the base is a platform"));
			}
		}

		if (natural != null) {
			checkSourceRegions(natural, scenes, errors);
		}

		Set<String> sampleIds = new HashSet<>();
		Map<String, Set<Integer>> viewpointsByScene = new HashMap<>();
		Map<String, Integer> baseSamplesByScene = new HashMap<>();
		Map<Split, int[]> splitTotals = new EnumMap<>(Split.class);
		for (SampleRecord sample : samples) {
			String label = "sample " + sample.sampleId() + ": ";
			if (!sampleIds.add(sample.sampleId())) {
				errors.add(label + "duplicate sample_id");
			}

			SceneRecord scene = scenesById.get(sample.sceneId());
			if (scene == null) {
				errors.add(label + "scene_id " + sample.sceneId() + " does not exist");
				continue;
			}

			if (sample.split() != scene.split()) {
				errors.add(label + "split " + sample.split().jsonName() + " differs from its scene's " + scene.split().jsonName());
			}

			if (sample.target().targetPresent() != scene.target().present()) {
				errors.add(label + "target_present differs from its scene");
			}

			if (sample.target().targetPresent() && sample.augmentation() == null
				&& !visibility.accepts(sample.target().targetStatus(), sample.target().visibilityRatio())) {
				errors.add(label + "target_status " + sample.target().targetStatus().jsonName() + " with visibility_ratio " + sample.target().visibilityRatio()
					+ " fails the dataset's " + visibility.rule().jsonName() + " rule");
			}

			if (sample.mode() != manifest.captureMode()) {
				errors.add(label + "capture mode " + sample.mode() + " differs from the dataset's " + manifest.captureMode());
			}

			if (sample.viewpointIndex() >= manifest.viewpointCount()) {
				errors.add(label + "viewpoint_index exceeds the configured " + manifest.viewpointCount() + " viewpoints");
			}

			if (sample.projectionOverrides().size() == 0
				&& (sample.rgb().width() != manifest.captureWidth() || sample.rgb().height() != manifest.captureHeight())) {
				errors.add(label + "image size differs from the configured " + manifest.captureWidth() + "x" + manifest.captureHeight());
			}

			if (sample.augmentation() == null) {
				if (!viewpointsByScene.computeIfAbsent(scene.sceneId(), id -> new HashSet<>()).add(sample.viewpointIndex())) {
					errors.add(label + "duplicate base viewpoint " + sample.viewpointIndex() + " for scene " + scene.sceneId());
				}

				baseSamplesByScene.merge(scene.sceneId(), 1, Integer::sum);
			} else if (!sampleIds.contains(sample.augmentation().sourceSampleId())) {
				errors.add(label + "augmentation source " + sample.augmentation().sourceSampleId() + " does not precede it");
			}

			splitTotals.computeIfAbsent(sample.split(), split -> new int[2])[1]++;
		}

		int present = 0;
		for (SceneRecord scene : scenes) {
			int base = baseSamplesByScene.getOrDefault(scene.sceneId(), 0);
			if (base != scene.acceptedSampleCount()) {
				errors.add("scene " + scene.sceneId() + ": accepted_sample_count " + scene.acceptedSampleCount() + " but " + base + " base samples reference it");
			}

			present += scene.target().present() ? 1 : 0;
			splitTotals.computeIfAbsent(scene.split(), split -> new int[2])[0]++;
		}

		int baseSamples = baseSamplesByScene.values().stream().mapToInt(Integer::intValue).sum();
		if (scenes.size() != manifest.counts().acceptedScenes()) {
			errors.add("manifest: counts.accepted_scenes is " + manifest.counts().acceptedScenes() + " but there are " + scenes.size() + " scenes");
		}

		if (baseSamples != manifest.counts().acceptedSamples()) {
			errors.add("manifest: counts.accepted_samples is " + manifest.counts().acceptedSamples() + " but there are " + baseSamples + " base samples");
		}

		if (manifest.lifecycle() == DatasetManifest.Lifecycle.COMPLETE) {
			checkComplete(manifest, scenes, present, baseSamplesByScene, splitTotals, errors);
		}

		return errors;
	}

	/**
	 * Split leakage between source patches: a patch's scenes share one split, every copied
	 * box has the base's size, and boxes of different splits lie at least the region
	 * separation apart horizontally.
	 */
	private static void checkSourceRegions(SceneBase.NaturalTerrain base, List<SceneRecord> scenes, List<String> errors) {
		Map<String, SceneRecord> byPatch = new LinkedHashMap<>();
		for (SceneRecord scene : scenes) {
			SceneRecord.SourcePatch patch = scene.sourcePatch();
			if (patch == null) {
				continue;
			}

			int[] min = patch.boundsMin();
			int[] max = patch.boundsMax();
			if (max[0] - min[0] + 1 != base.copyWidth() || max[2] - min[2] + 1 != base.copyLength() || max[1] - min[1] + 1 != base.height()) {
				errors.add("scene " + scene.sceneId() + ": source patch bounds do not have the base's copied size");
			}

			SceneRecord first = byPatch.putIfAbsent(patch.patchId(), scene);
			if (first != null && first.split() != scene.split()) {
				errors.add("scene " + scene.sceneId() + ": source patch " + patch.patchId() + " appears in both " + first.split().jsonName() + " and " + scene.split().jsonName());
			}
		}

		List<SceneRecord> patches = List.copyOf(byPatch.values());
		for (int a = 0; a < patches.size(); a++) {
			for (int b = a + 1; b < patches.size(); b++) {
				SceneRecord.SourcePatch first = patches.get(a).sourcePatch();
				SceneRecord.SourcePatch second = patches.get(b).sourcePatch();
				int gap = Math.max(gap(first.boundsMin()[0], first.boundsMax()[0], second.boundsMin()[0], second.boundsMax()[0]),
					gap(first.boundsMin()[2], first.boundsMax()[2], second.boundsMin()[2], second.boundsMax()[2]));
				if (patches.get(a).split() != patches.get(b).split() && gap < base.regionSeparationBlocks()) {
					errors.add("source patches " + first.patchId() + " and " + second.patchId() + " of different splits are " + gap + " blocks apart, under the "
						+ base.regionSeparationBlocks() + "-block region separation");
				}
			}
		}
	}

	/** Blocks strictly between two inclusive intervals; negative when they overlap. */
	private static int gap(int minA, int maxA, int minB, int maxB) {
		return Math.max(minB - maxA - 1, minA - maxB - 1);
	}

	/** The scene must sit in the class and split its index's slot fixes. */
	private static void checkSlot(SceneRecord scene, List<SceneSlots.Slot> slots, String algorithm, List<String> errors) {
		int index = scene.generation().sceneIndex();
		if (index >= slots.size()) {
			errors.add("scene " + scene.sceneId() + ": scene_index " + index + " exceeds the requested " + slots.size() + " scenes");
			return;
		}

		SceneSlots.Slot slot = slots.get(index);
		if (scene.split() != slot.split()) {
			errors.add("scene " + scene.sceneId() + ": split " + scene.split().jsonName() + " differs from the " + slot.split().jsonName() + " that " + algorithm + " assigns");
		}

		if (scene.target().present() != slot.targetPresent()) {
			errors.add("scene " + scene.sceneId() + ": target presence differs from the class " + algorithm + " assigns");
		}
	}

	private static void checkComplete(
		DatasetManifest manifest,
		List<SceneRecord> scenes,
		int present,
		Map<String, Integer> baseSamplesByScene,
		Map<Split, int[]> splitTotals,
		List<String> errors
	) {
		if (present != manifest.targetPresentScenes() || scenes.size() - present != manifest.targetAbsentScenes()) {
			errors.add("manifest: " + present + " target-present and " + (scenes.size() - present) + " absent scenes do not meet the quotas "
				+ manifest.targetPresentScenes() + "/" + manifest.targetAbsentScenes());
		}

		for (SceneRecord scene : scenes) {
			if (scene.acceptedSampleCount() != manifest.viewpointCount()) {
				errors.add("scene " + scene.sceneId() + ": has " + scene.acceptedSampleCount() + " base samples, but every accepted scene needs " + manifest.viewpointCount());
			}
		}

		for (Split split : Split.values()) {
			int[] totals = splitTotals.getOrDefault(split, new int[2]);
			DatasetManifest.SplitCount count = manifest.splitCounts().get(split);
			int baseSamples = 0;
			for (SceneRecord scene : scenes) {
				if (scene.split() == split) {
					baseSamples += baseSamplesByScene.getOrDefault(scene.sceneId(), 0);
				}
			}

			if (count.scenes() != totals[0] || count.samples() != baseSamples) {
				errors.add("manifest: splits.counts." + split.jsonName() + " is " + count.scenes() + " scenes/" + count.samples() + " samples but the records give "
					+ totals[0] + "/" + baseSamples);
			}
		}

		if (manifest.splitGrouping() == SplitGrouping.SOURCE_WORLD) {
			Map<Long, Split> splitByWorld = new HashMap<>();
			for (SceneRecord scene : scenes) {
				Split previous = splitByWorld.putIfAbsent(scene.sourcePatch().worldSeed(), scene.split());
				if (previous != null && previous != scene.split()) {
					errors.add("scene " + scene.sceneId() + ": source world " + scene.sourcePatch().worldSeed() + " appears in both " + previous.jsonName() + " and " + scene.split().jsonName());
				}
			}
		}
	}
}
