package io.github.thedeadbolt.honeyfactory.teacher;

import io.github.thedeadbolt.honeyfactory.control.BodySenses;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import org.jspecify.annotations.Nullable;

/**
 * What the teacher may use to decide tick {@code tick}: the body's pose and senses after
 * the previous tick, and the honey's cell only when the previous tick's end frame showed
 * it. This is the teacher's only path to the honey.
 *
 * @param honeySeen the honey's absolute cell, or null when it was not visible enough to see
 */
public record TeacherInput(int tick, CameraPose pose, BodySenses senses, int @Nullable [] honeySeen) {
}
