package io.github.thedeadbolt.honeyfactory.control;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.MaskStatistics;
import io.github.thedeadbolt.honeyfactory.contract.SampleRecord;
import io.github.thedeadbolt.honeyfactory.contract.TargetStatus;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import java.util.List;
import org.junit.jupiter.api.Test;

class ControlStepTest {
	private static final int SIZE = 16;

	/** A target whose mask covers a 4x4 square at (x, y). */
	private static SampleRecord.Target target(int x, int y) {
		byte[] mask = new byte[SIZE * SIZE];
		for (int row = y; row < y + 4; row++) {
			for (int column = x; column < x + 4; column++) {
				mask[row * SIZE + column] = (byte) 255;
			}
		}

		return SampleRecord.Target.of(true, true, MaskStatistics.of(mask, SIZE, SIZE), 16, 5.0);
	}

	private static ControlStep step(SampleRecord.Target target, boolean touchesEdge) {
		CameraPose pose = new CameraPose(10.5, 65.0, -3.25, 90.0F, 15.0F);
		MotorCommand student = new MotorCommand(1.0, 0.0, 2.0, 0.0, false, 0);
		BodySenses senses = BodySenses.of(pose, new double[] {0.1, 0.0, 0.0}, new MotorState(1.8, 0.0), true, false, false, MotorCommand.IDLE);
		TrueState truth = new TrueState(pose, new double[] {0.1, 0.0, 0.0}, "ab".repeat(32), 11L, 120L, target, touchesEdge);
		return new ControlStep(3, student, null, ControlStep.Executed.STUDENT, new MotorState(1.8, 0.0), senses, truth);
	}

	@Test
	void sensesCarryNothingABodyCouldNotFeel() {
		JsonObject senses = step(target(6, 6), false).sensesJson();
		for (String key : List.of("position", "yaw", "target", "layout_sha256", "velocity", "seed")) {
			assertFalse(senses.has(key), key + " leaked into the senses: " + senses);
		}

		for (String key : List.of("forward_speed", "strafe_speed", "vertical_speed", "yaw_rate", "pitch_rate", "pitch", "down", "on_ground", "bumped", "in_water", "last_command")) {
			assertTrue(senses.has(key), key + " is missing from the senses: " + senses);
		}
	}

	@Test
	void truthCarriesTheWorldState() {
		JsonObject truth = step(target(6, 6), false).truthJson();
		for (String key : List.of("position", "yaw", "pitch", "velocity", "layout_sha256", "seed", "server_tick", "target", "target_touches_edge")) {
			assertTrue(truth.has(key), key + " is missing from the truth: " + truth);
		}

		assertEquals(TargetStatus.FULLY_VISIBLE.jsonName(), truth.getAsJsonObject("target").get("target_status").getAsString());
	}

	@Test
	void motionIsMeasuredInTheBodyFrame() {
		// Yaw 90 faces -X, so moving +X is moving backwards; its right is -Z.
		BodySenses senses = BodySenses.of(new CameraPose(0, 64, 0, 90.0F, 0.0F), new double[] {1.0, -0.5, 2.0}, MotorState.ZERO, false, false, false, MotorCommand.IDLE);
		assertEquals(-1.0, senses.forwardSpeed(), 1e-9);
		assertEquals(-2.0, senses.strafeSpeed(), 1e-9);
		assertEquals(-0.5, senses.verticalSpeed(), 1e-9);
	}

	@Test
	void downIsGivenInCameraCoordinates() {
		assertArrayEquals(new double[] {0.0, -1.0, 0.0}, BodySenses.of(new CameraPose(0, 64, 0, 37.0F, 0.0F), new double[3], MotorState.ZERO, true, false, false, MotorCommand.IDLE).down(), 1e-9);
		assertArrayEquals(new double[] {0.0, 0.0, 1.0}, BodySenses.of(new CameraPose(0, 64, 0, 37.0F, 90.0F), new double[3], MotorState.ZERO, true, false, false, MotorCommand.IDLE).down(), 1e-9);
	}

	@Test
	void aTargetAtTheFrameEdgeIsStillARecord() {
		ControlStep step = step(target(0, 6), true);
		assertTrue(step.truth().targetTouchesEdge());
		assertTrue(step.truthJson().get("target_touches_edge").getAsBoolean());
	}

	@Test
	void commandsRecordBothSidesAndWhichRan() {
		JsonObject commands = step(target(6, 6), false).commandsJson();
		assertEquals(1.0, commands.getAsJsonObject("student").get("forward").getAsDouble());
		assertTrue(commands.get("teacher").isJsonNull());
		assertEquals("student", commands.get("executed").getAsString());
		assertEquals(1.8, commands.getAsJsonObject("achieved").get("yaw_rate").getAsDouble());
	}
}
