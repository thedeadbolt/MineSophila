package io.github.thedeadbolt.honeyfactory.contract;

/**
 * How a viewpoint's camera is oriented once its position is drawn.
 *
 * <ul>
 *   <li>{@code target_jitter}: aim at the target's block centre, then add uniform yaw and
 *       pitch jitter. The target lands near the image centre.</li>
 *   <li>{@code image_uniform}: draw the block centre's output pixel uniformly over the
 *       image, then turn the camera so the centre lands there. A panorama's rows follow
 *       from the target's elevation alone, so only its column is drawn. Draws that would
 *       put any part of the block outside the image are rejected.</li>
 * </ul>
 */
public enum ViewpointAim implements JsonEnum {
	TARGET_JITTER,
	IMAGE_UNIFORM;

	public static ViewpointAim fromJson(String name) {
		return JsonEnum.parse(ViewpointAim.class, name);
	}
}
