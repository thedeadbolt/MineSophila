package io.github.thedeadbolt.honeyfactory.teacher;

import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.control.MotorLimits;
import io.github.thedeadbolt.honeyfactory.scene.SightGrid;

/**
 * What a teacher knows of a clip before it starts: the scene's blocks to look through (only
 * as far as it has seen them), the arena's core in absolute coordinates, the body's limits,
 * how many visible pixels count as seeing the honey, the camera it sees through, and how
 * close counts as reaching the honey.
 */
public record TeacherContext(SightGrid grid, BlockBox walkable, MotorLimits limits, int minVisiblePixels, double eyeHeight, CaptureProjection projection, double reachDistanceBlocks) {
}
