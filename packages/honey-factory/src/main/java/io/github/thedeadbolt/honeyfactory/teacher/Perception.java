package io.github.thedeadbolt.honeyfactory.teacher;

import io.github.thedeadbolt.honeyfactory.control.BodySenses;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import org.jspecify.annotations.Nullable;

/**
 * The one place that reads the honey's true position for the teacher. It passes the honey on
 * only when the observed frame showed at least the configured number of visible pixels, so
 * the teacher cannot act on honey the fly could not have seen; it also updates the teacher's
 * memory and seen map from the same observation.
 */
public final class Perception {
	private Perception() {
	}

	/**
	 * What the teacher may know at {@code tick}, from the body's pose and senses after the
	 * previous tick and that tick's end frame.
	 *
	 * @param trueTarget the honey's cell in the scene, or null without honey
	 * @param visiblePixels the honey's visible pixels in the observed frame
	 */
	public static TeacherInput perceive(int tick, CameraPose pose, BodySenses senses, int @Nullable [] trueTarget, int visiblePixels, TeacherContext context, SeenMap seen,
		HoneyMemory memory) {
		seen.observe(pose, context.eyeHeight(), context.projection(), context.grid());
		int[] honey = trueTarget != null && visiblePixels >= context.minVisiblePixels() ? trueTarget.clone() : null;
		if (honey != null) {
			memory.sawAt(honey, tick);
		}

		return new TeacherInput(tick, pose, senses, honey, seen, memory);
	}
}
