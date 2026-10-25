package io.github.thedeadbolt.honeyfactory.control;

import io.github.thedeadbolt.honeyfactory.environment.Action;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The M7 actions as per-tick motor commands. A turn or look starts and ends at rest: its
 * rate climbs by the acceleration limit, cruises at most at the maximum, and slows again,
 * so it lands exactly on the requested angle with the last tick carrying the remainder. A
 * move is that many ticks of full forward, a wait that many idle ticks, and a stop none.
 */
public final class ActionMacros {
	private static final double EPSILON = 1e-9;

	private ActionMacros() {
	}

	/**
	 * @throws IllegalArgumentException for {@link Action.Mine}, which is out of scope
	 */
	public static List<MotorCommand> expand(Action action, MotorLimits limits) {
		return switch (action) {
			case Action.Turn turn -> profile(turn.degrees(), limits.maxTurnAccel(), limits.maxTurnRate()).stream()
				.map(rate -> new MotorCommand(0.0, 0.0, rate, 0.0, false, 0)).toList();
			case Action.Look look -> profile(look.degrees(), limits.maxPitchAccel(), limits.maxPitchRate()).stream()
				.map(rate -> new MotorCommand(0.0, 0.0, 0.0, rate, false, 0)).toList();
			case Action.MoveForward move -> Collections.nCopies(move.ticks(), new MotorCommand(1.0, 0.0, 0.0, 0.0, false, 0));
			case Action.Wait wait -> Collections.nCopies(wait.ticks(), MotorCommand.IDLE);
			case Action.Stop stop -> List.of();
			case Action.Mine mine -> throw new IllegalArgumentException("MINE is not supported; mining is out of scope");
		};
	}

	/**
	 * Rates covering {@code degrees} from rest to rest. Each tick takes the fastest rate from
	 * which the body could still stop in time, slowing by {@code accel} per tick; a tick that
	 * would overshoot, or reach rest early, takes the remainder instead.
	 */
	static List<Double> profile(double degrees, double accel, double max) {
		double sign = Math.signum(degrees);
		double left = Math.abs(degrees);
		double rate = 0.0;
		List<Double> rates = new ArrayList<>();
		while (left > EPSILON) {
			double up = Math.min(rate + accel, max);
			double next;
			if (up + stopping(up, accel) <= left + EPSILON) {
				next = up;
			} else if (rate > 0.0 && rate + stopping(rate, accel) <= left + EPSILON) {
				next = rate;
			} else {
				next = Math.max(rate - accel, 0.0);
			}

			if (next <= EPSILON || next > left) {
				next = left;
			}

			rates.add(sign * next);
			left -= next;
			rate = next;
		}

		return rates;
	}

	/** The angle covered while slowing from {@code rate} by {@code accel} per tick until rest. */
	private static double stopping(double rate, double accel) {
		double total = 0.0;
		for (double slower = rate - accel; slower > EPSILON; slower -= accel) {
			total += slower;
		}

		return total;
	}
}
