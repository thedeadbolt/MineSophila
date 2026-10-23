package io.github.thedeadbolt.honeyfactory.gametest;

import io.github.thedeadbolt.honeyfactory.capture.CameraFrame;
import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.capture.PanoramicProjection;
import io.github.thedeadbolt.honeyfactory.scene.ArenaLayout;
import io.github.thedeadbolt.honeyfactory.scene.Placement;
import io.github.thedeadbolt.honeyfactory.scene.ScenePlan;
import java.util.ArrayList;
import java.util.List;

/**
 * An independent CPU cross-check of rendered masks: rays through pixel centres, tested
 * against the target's block cell and the fixture occluders' model boxes.
 *
 * <p>Only robust pixels are judged: a pixel counts when the rays through its centre and
 * four points 0.75 pixels away all agree. Rasterisation may differ from rays at edges,
 * never inside a region. In a panorama the horizontal offsets widen by 1 / cos(elevation),
 * up to tenfold, so they span the same angle as the vertical ones even near the poles,
 * where a pixel is horizontally tiny.
 */
final class RayCheck {
	private static final double[][] OFFSETS = {{0, 0}, {0.75, 0}, {-0.75, 0}, {0, 0.75}, {0, -0.75}};
	private static final double P = 1.0 / 16.0;

	/** Mismatches against the rays; {@code definitelyOccluded} counts robustly hidden target pixels. */
	record Result(int unoccludedMismatches, int visibleMismatches, int definitelyOccluded) {
	}

	private final List<double[][]> occluders = new ArrayList<>();
	private final boolean occludersExact;

	/**
	 * @param occludersExact whether the occluder boxes are the rendered geometry; cutout
	 *     leaves have holes no box describes, so only target coverage is judged for them
	 */
	RayCheck(ScenePlan plan, ArenaLayout arena, boolean occludersExact) {
		this.occludersExact = occludersExact;
		int[] origin = arena.core().min();
		for (Placement placement : plan.delta()) {
			if (placement.equals(plan.target())) {
				continue;
			}

			for (double[][] box : modelBoxes(placement.state())) {
				this.occluders.add(new double[][] {
					{origin[0] + placement.x() + box[0][0], origin[1] + placement.y() + box[0][1], origin[2] + placement.z() + box[0][2]},
					{origin[0] + placement.x() + box[1][0], origin[1] + placement.y() + box[1][1], origin[2] + placement.z() + box[1][2]},
				});
			}
		}
	}

	Result check(CaptureProjection projection, CameraFrame camera, int[] target, CapturedImages images) {
		double[][] targetBox = {{target[0], target[1], target[2]}, {target[0] + 1, target[1] + 1, target[2] + 1}};
		int unoccludedMismatches = 0;
		int visibleMismatches = 0;
		int definitelyOccluded = 0;
		for (int y = 0; y < images.height(); y++) {
			double widen = projection instanceof PanoramicProjection panorama ? 1.0 / Math.max(Math.cos(Math.toRadians(panorama.elevationDegrees(y + 0.5))), 0.1) : 1.0;
			for (int x = 0; x < images.width(); x++) {
				int hitCount = 0;
				int visibleCount = 0;
				for (double[] offset : OFFSETS) {
					double[] direction = projection.direction(camera, x + 0.5 + offset[0] * widen, y + 0.5 + offset[1]);
					double targetT = intersect(camera.eye(), direction, targetBox);
					if (targetT < Double.POSITIVE_INFINITY) {
						hitCount++;
						boolean blocked = false;
						for (double[][] box : this.occluders) {
							blocked |= intersect(camera.eye(), direction, box) < targetT;
						}

						visibleCount += blocked ? 0 : 1;
					}
				}

				int p = y * images.width() + x;
				boolean unoccluded = images.unoccludedMask()[p] != 0;
				boolean visible = images.visibleMask()[p] != 0;
				if (hitCount == OFFSETS.length && !unoccluded || hitCount == 0 && unoccluded) {
					unoccludedMismatches++;
				}

				boolean robustlyHidden = this.occludersExact && hitCount == OFFSETS.length && visibleCount == 0;
				if (robustlyHidden && visible) {
					definitelyOccluded++;
				}

				if (this.occludersExact && hitCount == OFFSETS.length && visibleCount == OFFSETS.length && !visible) {
					visibleMismatches++;
				}
			}
		}

		return new Result(unoccludedMismatches, visibleMismatches, definitelyOccluded);
	}

	/** Pixels whose centre ray hits the target block cell. */
	static int targetPixels(CaptureProjection projection, CameraFrame camera, int[] target) {
		double[][] box = {{target[0], target[1], target[2]}, {target[0] + 1, target[1] + 1, target[2] + 1}};
		int count = 0;
		for (int y = 0; y < projection.height(); y++) {
			for (int x = 0; x < projection.width(); x++) {
				count += intersect(camera.eye(), projection.direction(camera, x + 0.5, y + 0.5), box) < Double.POSITIVE_INFINITY ? 1 : 0;
			}
		}

		return count;
	}

	/** Model boxes, in block-local units, of the fixture states; anything unlisted is a full cube. */
	private static List<double[][]> modelBoxes(String state) {
		if (state.startsWith("minecraft:stone_slab[type=bottom")) {
			return List.<double[][]>of(box(0, 0, 0, 16, 8, 16));
		}

		if (state.startsWith("minecraft:stone_stairs[facing=north,half=bottom")) {
			return List.<double[][]>of(box(0, 0, 0, 16, 8, 16), box(0, 8, 0, 16, 16, 8));
		}

		if (state.startsWith("minecraft:oak_fence[")) {
			List<double[][]> boxes = new ArrayList<>(List.<double[][]>of(box(6, 0, 6, 10, 16, 10)));
			if (state.contains("east=true")) {
				boxes.add(box(10, 6, 7, 16, 9, 9));
				boxes.add(box(10, 12, 7, 16, 15, 9));
			}

			if (state.contains("west=true")) {
				boxes.add(box(0, 6, 7, 6, 9, 9));
				boxes.add(box(0, 12, 7, 6, 15, 9));
			}

			return boxes;
		}

		return List.<double[][]>of(box(0, 0, 0, 16, 16, 16));
	}

	private static double[][] box(double x0, double y0, double z0, double x1, double y1, double z1) {
		return new double[][] {{x0 * P, y0 * P, z0 * P}, {x1 * P, y1 * P, z1 * P}};
	}

	/** Entry distance of a ray into a box, or infinity when it misses. */
	private static double intersect(double[] origin, double[] direction, double[][] box) {
		double near = 0.0;
		double far = Double.POSITIVE_INFINITY;
		for (int axis = 0; axis < 3; axis++) {
			if (Math.abs(direction[axis]) < 1e-12) {
				if (origin[axis] < box[0][axis] || origin[axis] > box[1][axis]) {
					return Double.POSITIVE_INFINITY;
				}

				continue;
			}

			double t1 = (box[0][axis] - origin[axis]) / direction[axis];
			double t2 = (box[1][axis] - origin[axis]) / direction[axis];
			near = Math.max(near, Math.min(t1, t2));
			far = Math.min(far, Math.max(t1, t2));
		}

		return near <= far ? near : Double.POSITIVE_INFINITY;
	}
}
