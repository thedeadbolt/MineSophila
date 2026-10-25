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
 * Target-present captures must then lie wholly inside the frame, satisfy the level's
 * visibility rule and show at least the configured number of visible pixels.
 *
 * <p>The visibility ratio measures occlusion by the scene, so a target cut off by the frame
 * would look more visible than it is: its unoccluded mask is cut off too. A capture whose
 * unoccluded mask reaches the frame's edge is therefore rejected as {@value #FRAME_CLIPPED}.
 * That covers a perspective view's sides and a panorama's elevation limits and blind-sector
 * sides.
 */
public final class ObservationCheck {
	public static final String MASK_VALIDATION = "mask_validation";
	public static final String VISIBILITY = "visibility";
	public static final String FRAME_CLIPPED = "frame_clipped";
	public static final String TOO_FEW_PIXELS = "too_few_pixels";

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
		if (present && images.unoccludedTouchesEdge()) {
			return new Outcome.Rejected(FRAME_CLIPPED, "the target reaches the edge of the frame, so part of it may lie outside");
		}

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

		if (present && metadata.visiblePixels() < visibility.minVisiblePixels()) {
			return new Outcome.Rejected(TOO_FEW_PIXELS, metadata.visiblePixels() + " visible pixels, below the minimum of " + visibility.minVisiblePixels());
		}

		return new Outcome.Accepted(metadata);
	}
}
