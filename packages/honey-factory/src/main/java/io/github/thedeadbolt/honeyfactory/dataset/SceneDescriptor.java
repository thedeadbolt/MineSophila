package io.github.thedeadbolt.honeyfactory.dataset;

import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.Ids;
import io.github.thedeadbolt.honeyfactory.contract.SceneSeeds;
import io.github.thedeadbolt.honeyfactory.contract.Split;

/** One generation attempt of a scene slot: identity, class, split and derived seeds. */
public record SceneDescriptor(SceneSlots.Slot slot, int attemptIndex, SceneSeeds seeds) {
	public static SceneDescriptor of(ResolvedConfig config, SceneSlots.Slot slot, int attemptIndex) {
		return new SceneDescriptor(slot, attemptIndex, SceneSeeds.derive(config.seed(), slot.sceneIndex(), attemptIndex));
	}

	public String sceneId() {
		return this.slot.sceneId();
	}

	public Split split() {
		return this.slot.split();
	}

	public SampleDescriptor sample(int viewpointIndex) {
		String sampleId = Ids.sampleId(sceneId(), viewpointIndex);
		return new SampleDescriptor(sampleId, sceneId(), split(), viewpointIndex, Ids.rgbPath(split(), sampleId), Ids.maskPath(split(), sampleId));
	}
}
