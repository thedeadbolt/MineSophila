package io.github.thedeadbolt.honeyfactory.generation;

import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.contract.Split;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Running counts of one generation run, in the manifest's terms. Every attempt ends as
 * exactly one of accepted, rejected or failed.
 */
final class GenerationTally {
	private final int requestedScenes;
	private final Map<String, Integer> rejectionReasons = new LinkedHashMap<>();
	private final Map<Split, DatasetManifest.SplitCount> splitCounts = new EnumMap<>(Split.class);
	private int attempted;
	private int accepted;
	private int rejected;
	private int failed;
	private int acceptedSamples;
	private int acceptedClips;

	GenerationTally(int requestedScenes) {
		this.requestedScenes = requestedScenes;
		for (Split split : Split.values()) {
			this.splitCounts.put(split, new DatasetManifest.SplitCount(0, 0));
		}
	}

	int attempted() {
		return this.attempted;
	}

	void attempt() {
		this.attempted++;
	}

	void accept(Split split, int samples) {
		this.accepted++;
		this.acceptedSamples += samples;
		DatasetManifest.SplitCount count = this.splitCounts.get(split);
		this.splitCounts.put(split, new DatasetManifest.SplitCount(count.scenes() + 1, count.samples() + samples));
	}

	/** A clip dataset's accepted scene and its clips. */
	void acceptClips(Split split, int clips) {
		this.accepted++;
		this.acceptedClips += clips;
		DatasetManifest.SplitCount count = this.splitCounts.get(split);
		this.splitCounts.put(split, new DatasetManifest.SplitCount(count.scenes() + 1, 0, count.clips() + clips));
	}

	void reject(String reason) {
		this.rejected++;
		this.rejectionReasons.merge(reason, 1, Integer::sum);
	}

	void fail() {
		this.failed++;
	}

	DatasetManifest.Counts counts() {
		return new DatasetManifest.Counts(this.requestedScenes, this.attempted, this.accepted, this.rejected, this.failed, this.acceptedSamples, this.acceptedClips, Map.copyOf(this.rejectionReasons));
	}

	Map<Split, DatasetManifest.SplitCount> splitCounts() {
		return Map.copyOf(this.splitCounts);
	}

	DatasetGenerator.Progress progress(Duration elapsed) {
		return new DatasetGenerator.Progress(this.requestedScenes, this.accepted, this.attempted, this.acceptedSamples + this.acceptedClips, this.rejected, Map.copyOf(this.rejectionReasons), elapsed);
	}
}
