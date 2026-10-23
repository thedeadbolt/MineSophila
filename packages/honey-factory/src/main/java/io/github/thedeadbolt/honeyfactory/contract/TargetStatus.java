package io.github.thedeadbolt.honeyfactory.contract;

import org.jspecify.annotations.Nullable;

/** One unambiguous target state per sample, derived from mask evidence in specification order. */
public enum TargetStatus {
	ABSENT("absent"),
	OUT_OF_VIEW("out_of_view"),
	SUBPIXEL("subpixel"),
	FULLY_OCCLUDED("fully_occluded"),
	FULLY_VISIBLE("fully_visible"),
	PARTIALLY_VISIBLE("partially_visible");

	private final String jsonName;

	TargetStatus(String jsonName) {
		this.jsonName = jsonName;
	}

	public String jsonName() {
		return this.jsonName;
	}

	public static TargetStatus fromJson(String name) {
		for (TargetStatus status : values()) {
			if (status.jsonName.equals(name)) {
				return status;
			}
		}

		throw new IllegalArgumentException("Unknown target status '" + name + "'");
	}

	/**
	 * Derives the status, rejecting evidence the contract forbids: negative counts, more
	 * visible than unoccluded pixels, or pixels for an absent or out-of-view target.
	 */
	public static TargetStatus derive(boolean present, boolean inProjection, int visiblePixels, int unoccludedPixels) {
		if (visiblePixels < 0 || unoccludedPixels < 0) {
			throw new IllegalArgumentException("Pixel counts must be non-negative");
		}

		if (visiblePixels > unoccludedPixels) {
			throw new IllegalArgumentException("Visible pixels exceed unoccluded pixels");
		}

		if (!present || !inProjection) {
			if (inProjection) {
				throw new IllegalArgumentException("An absent target cannot be in the projection");
			}

			if (unoccludedPixels > 0) {
				throw new IllegalArgumentException("A target outside the projection cannot have mask pixels");
			}

			return present ? OUT_OF_VIEW : ABSENT;
		}

		if (unoccludedPixels == 0) {
			return SUBPIXEL;
		}

		if (visiblePixels == 0) {
			return FULLY_OCCLUDED;
		}

		return visiblePixels == unoccludedPixels ? FULLY_VISIBLE : PARTIALLY_VISIBLE;
	}

	/** {@code visible / unoccluded} as an IEEE double, or null when nothing is unoccluded. */
	public static @Nullable Double visibilityRatio(int visiblePixels, int unoccludedPixels) {
		return unoccludedPixels == 0 ? null : (double) visiblePixels / unoccludedPixels;
	}
}
