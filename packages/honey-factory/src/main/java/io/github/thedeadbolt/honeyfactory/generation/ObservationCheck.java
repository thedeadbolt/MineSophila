package io.github.thedeadbolt.honeyfactory.generation;

import io.github.thedeadbolt.honeyfactory.capture.CameraFrame;
import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.MaskStatistics;
import io.github.thedeadbolt.honeyfactory.contract.SampleRecord;
import io.github.thedeadbolt.honeyfactory.contract.TargetStatus;
import org.jspecify.annotations.Nullable;

/**
 * Turns one capture into target metadata, or the reason it cannot become a sample.
 *
 * <p>The masks must be binary with every visible pixel inside the unoccluded mask. Whether
 * the target is in the projection is decided geometrically from its block cell, so a target
 * in view that rasterises to no pixel is {@code subpixel} rather than {@code out_of_view}.
 * Target-present captures must then satisfy the level's visibility rule.
 */
public final class ObservationCheck {
	public static final String MASK_VALIDATION = "mask_validation";
	public static final String VISIBILITY = "visibility";

	/** Exactly one of {@code target} and {@code rejection} is non-null. */
	public record Outcome(SampleRecord.@Nullable Target target, @Nullable String rejection, @Nullable String detail) {
		static Outcome rejected(String reason, String detail) {
			return new Outcome(null, reason, detail);
		}
	}

	private ObservationCheck() {
	}

	/**
	 * @param target the absolute target block, or null when the scene has none
	 */
	public static Outcome check(ResolvedConfig.Visibility visibility, CaptureProjection projection, CameraFrame camera, int @Nullable [] target, CapturedImages images) {
		if (images.width() != projection.width() || images.height() != projection.height()) {
			return Outcome.rejected(MASK_VALIDATION, "capture is " + images.width() + "x" + images.height() + ", expected " + projection.width() + "x" + projection.height());
		}

		String defect = images.maskDefect();
		if (defect != null) {
			return Outcome.rejected(MASK_VALIDATION, defect);
		}

		boolean present = target != null;
		boolean inProjection = present && projection.intersects(camera, new double[] {target[0], target[1], target[2]}, new double[] {target[0] + 1, target[1] + 1, target[2] + 1});
		MaskStatistics visible = MaskStatistics.of(images.visibleMask(), images.width(), images.height());
		int unoccluded = images.unoccludedPixels();
		SampleRecord.Target metadata;
		try {
			Double distance = present ? camera.distanceTo(new double[] {target[0] + 0.5, target[1] + 0.5, target[2] + 0.5}) : null;
			metadata = SampleRecord.Target.of(present, inProjection, visible, unoccluded, distance);
		} catch (IllegalArgumentException e) {
			return Outcome.rejected(MASK_VALIDATION, e.getMessage());
		}

		if (present && !visibility.rule().accepts(metadata.targetStatus(), metadata.visibilityRatio(), visibility.minRatio(), visibility.maxRatio())) {
			return Outcome.rejected(VISIBILITY, metadata.targetStatus().jsonName() + " with ratio " + metadata.visibilityRatio() + " fails " + visibility.rule().jsonName());
		}

		return new Outcome(metadata, null, null);
	}
}
