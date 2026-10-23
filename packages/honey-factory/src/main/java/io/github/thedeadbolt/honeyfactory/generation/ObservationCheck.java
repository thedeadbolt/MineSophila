package io.github.thedeadbolt.honeyfactory.generation;

import io.github.thedeadbolt.honeyfactory.capture.CameraFrame;
import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
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

	/** The capture's target metadata, or the reason it cannot become a sample. */
	public sealed interface Outcome {
		record Accepted(SampleRecord.Target target) implements Outcome {
		}

		/** {@code reason} is the counted rejection reason; {@code detail} explains this case. */
		record Rejected(String reason, String detail) implements Outcome {
		}
	}

	private ObservationCheck() {
	}

	/**
	 * @param target the target's block cell, or null when the scene has none
	 */
	public static Outcome check(ResolvedConfig.Visibility visibility, CaptureProjection projection, CameraFrame camera, @Nullable BlockBox target, CapturedImages images) {
		if (images.width() != projection.width() || images.height() != projection.height()) {
			return new Outcome.Rejected(MASK_VALIDATION, "capture is " + images.width() + "x" + images.height() + ", expected " + projection.width() + "x" + projection.height());
		}

		String defect = images.maskDefect();
		if (defect != null) {
			return new Outcome.Rejected(MASK_VALIDATION, defect);
		}

		boolean present = target != null;
		boolean inProjection = present && projection.intersects(camera, target);
		MaskStatistics visible = MaskStatistics.of(images.visibleMask(), images.width(), images.height());
		int unoccluded = images.unoccludedPixels();
		SampleRecord.Target metadata;
		try {
			Double distance = present ? camera.distanceTo(target.center()) : null;
			metadata = SampleRecord.Target.of(present, inProjection, visible, unoccluded, distance);
		} catch (IllegalArgumentException e) {
			return new Outcome.Rejected(MASK_VALIDATION, e.getMessage());
		}

		if (present && !visibility.rule().accepts(metadata.targetStatus(), metadata.visibilityRatio(), visibility.minRatio(), visibility.maxRatio())) {
			return new Outcome.Rejected(VISIBILITY, metadata.targetStatus().jsonName() + " with ratio " + metadata.visibilityRatio() + " fails " + visibility.rule().jsonName());
		}

		return new Outcome.Accepted(metadata);
	}
}
