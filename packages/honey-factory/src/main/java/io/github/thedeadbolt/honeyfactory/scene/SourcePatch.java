package io.github.thedeadbolt.honeyfactory.scene;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One accepted source patch: the copied box in the source dimension, inclusive, and the
 * candidate that produced it. {@code contentSha256} is the {@code layout-v1} hash of the
 * copy in the arena before any scene changes it, recorded as the scene's template checksum.
 * {@code rejectedDraws} counts the earlier candidates of this patch by rejection reason.
 */
public record SourcePatch(
	String patchId,
	long worldSeed,
	String dimension,
	int[] sourceMin,
	int[] sourceMax,
	String generatorVersion,
	int patchIndex,
	int draw,
	String contentSha256,
	Map<String, Integer> rejectedDraws
) {
	public SourcePatch {
		sourceMin = sourceMin.clone();
		sourceMax = sourceMax.clone();
		rejectedDraws = Map.copyOf(rejectedDraws);
	}

	/** {@code <world seed>/<dimension>/<min x,y,z>/<max x,y,z>/<generator version>}. */
	public static String id(long worldSeed, String dimension, int[] min, int[] max, String generatorVersion) {
		return worldSeed + "/" + dimension + "/" + min[0] + "," + min[1] + "," + min[2] + "/" + max[0] + "," + max[1] + "," + max[2] + "/" + generatorVersion;
	}

	@Override
	public int[] sourceMin() {
		return this.sourceMin.clone();
	}

	@Override
	public int[] sourceMax() {
		return this.sourceMax.clone();
	}

	@Override
	public boolean equals(@Nullable Object other) {
		return other instanceof SourcePatch that && this.patchId.equals(that.patchId) && this.contentSha256.equals(that.contentSha256);
	}

	@Override
	public int hashCode() {
		return this.patchId.hashCode();
	}
}
