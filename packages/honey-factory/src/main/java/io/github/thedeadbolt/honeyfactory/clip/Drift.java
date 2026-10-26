package io.github.thedeadbolt.honeyfactory.clip;

import io.github.thedeadbolt.honeyfactory.contract.ClipSettings;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.control.MotorLimits;
import java.util.SplittableRandom;

/**
 * Temporally correlated departures from the teacher's command (DART-style): the body is
 * nudged off course and the teacher, recorded beside it, recovers. Episodes start at a rate
 * that makes about {@code rate} of ticks drift and last a geometric number of ticks with mean
 * {@code meanTicks}. Each adds a half-sine bump to the turn rate and the forward speed, up to
 * the configured amplitudes, so it rises and falls smoothly; the result stays within the
 * motor limits. Call {@link #apply} once per tick, in order.
 */
public final class Drift {
	private final SplittableRandom random;
	private final ClipSettings.Drift settings;
	private final MotorLimits limits;
	/** The chance an episode starts on a tick without one. */
	private final double startChance;
	private int length;
	private int step;
	private double turn;
	private double forward;
	private boolean active;

	public Drift(long seed, ClipSettings.Drift settings, MotorLimits limits) {
		this.random = new SplittableRandom(seed);
		this.settings = settings;
		this.limits = limits;
		double rate = settings.rate();
		// A share r of ticks drifting, with episodes of mean L and gaps of mean 1/p: r = L / (L + 1/p).
		this.startChance = rate <= 0.0 ? 0.0 : Math.min(1.0, rate / (settings.meanTicks() * (1.0 - rate)));
	}

	/** The command to execute this tick: {@code teacher}, plus the drift when an episode runs. */
	public MotorCommand apply(MotorCommand teacher) {
		if (this.step >= this.length && this.random.nextDouble() < this.startChance) {
			this.length = geometric(this.settings.meanTicks());
			this.step = 0;
			this.turn = this.random.nextDouble(-1.0, 1.0) * this.settings.maxTurnRate();
			this.forward = this.random.nextDouble(-1.0, 1.0) * this.settings.maxForward();
		}

		this.active = this.step < this.length;
		if (!this.active) {
			return teacher;
		}

		double bump = Math.sin(Math.PI * (this.step + 0.5) / this.length);
		this.step++;
		return new MotorCommand(
			Math.clamp(teacher.forward() + this.forward * bump, -1.0, 1.0),
			teacher.strafe(),
			Math.clamp(teacher.turnRate() + this.turn * bump, -this.limits.maxTurnRate(), this.limits.maxTurnRate()),
			teacher.pitchRate(),
			teacher.jump(),
			teacher.delayTicks()
		);
	}

	/** Whether the last {@link #apply} drifted. */
	public boolean active() {
		return this.active;
	}

	/** A length of at least one tick, geometric with the given mean. */
	private int geometric(double mean) {
		double continueChance = 1.0 - 1.0 / mean;
		int ticks = 1;
		while (this.random.nextDouble() < continueChance) {
			ticks++;
		}

		return ticks;
	}
}
