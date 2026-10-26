package io.github.thedeadbolt.honeyfactory.replay;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.clip.ClipEnvironment;
import io.github.thedeadbolt.honeyfactory.clip.ClipLabels;
import io.github.thedeadbolt.honeyfactory.contract.ClipRecord;
import io.github.thedeadbolt.honeyfactory.contract.ContractException;
import io.github.thedeadbolt.honeyfactory.contract.DatasetKind;
import io.github.thedeadbolt.honeyfactory.contract.FrameRecord;
import io.github.thedeadbolt.honeyfactory.contract.Ids;
import io.github.thedeadbolt.honeyfactory.contract.JsonWrite;
import io.github.thedeadbolt.honeyfactory.contract.Jsonl;
import io.github.thedeadbolt.honeyfactory.contract.SampleRecord;
import io.github.thedeadbolt.honeyfactory.contract.Weather;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.scene.SceneBase;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Replays a motion clip (M12-2): its scene is rebuilt from its seeds, the body reset at the
 * clip's start, and the recorded executed commands run again. Each replayed frame is
 * compared with the stored one: the pose and labels (status, pixel counts, box, bearing)
 * must match, the visible mask byte for byte, and the RGB byte for byte unless the codec is
 * lossy or the scene animates (a natural base's water and lava, rain).
 *
 * <p>Stored frames are decoded as the replay goes, one at a time, since a full-size clip is
 * far too large to hold decoded.
 */
public final class ReplayClip implements AutoCloseable {
	/** How far a replayed pose may lie from the recorded one, in blocks and degrees. */
	static final double POSE_TOLERANCE = 1e-4;
	/** Differences listed in a result; the counts cover every frame. */
	static final int LISTED = 10;

	/** How a replay compared, over every frame. */
	public record Result(String clipId, int frames, int poseMismatches, int labelMismatches, int maskMismatches, int rgbMismatches, boolean rgbCompared, List<String> differences) {
		/** Masks, labels and poses match everywhere, and RGB too where it is compared. */
		public boolean exact() {
			return this.poseMismatches == 0 && this.labelMismatches == 0 && this.maskMismatches == 0 && this.rgbMismatches == 0;
		}

		public JsonObject toJson() {
			JsonObject json = new JsonObject();
			json.addProperty("clip_id", this.clipId);
			json.addProperty("frames", this.frames);
			json.addProperty("exact", exact());
			json.addProperty("pose_mismatches", this.poseMismatches);
			json.addProperty("label_mismatches", this.labelMismatches);
			json.addProperty("mask_mismatches", this.maskMismatches);
			json.addProperty("rgb_compared", this.rgbCompared);
			json.addProperty("rgb_mismatches", this.rgbMismatches);
			json.add("differences", JsonWrite.strings(this.differences));
			return json;
		}
	}

	private final ReplayScene scene;
	private final ClipRecord clip;
	private final List<FrameRecord> frames;
	private final boolean rgbCompared;
	private Process rgb;
	private Process mask;
	private int compared;
	private int poseMismatches;
	private int labelMismatches;
	private int maskMismatches;
	private int rgbMismatches;
	private final List<String> differences = new ArrayList<>();

	private ReplayClip(ReplayScene scene, ClipRecord clip, List<FrameRecord> frames) {
		this.scene = scene;
		this.clip = clip;
		this.frames = List.copyOf(frames);
		boolean animated = scene.config().scene().base() instanceof SceneBase.NaturalTerrain || scene.scene().environment().weather() != Weather.CLEAR;
		this.rgbCompared = clip.codec().lossless() && !animated;
	}

	/**
	 * Reads clip {@code clipId} of the clip dataset in {@code directory}, with its scene.
	 *
	 * @throws ReplayException NOT_FOUND, INVALID_DATASET, UNAVAILABLE or MISMATCH
	 */
	public static ReplayClip load(Path directory, String clipId) {
		if (!Ids.CLIP_ID.matcher(clipId).matches()) {
			throw new ReplayException(ReplayException.Reason.NOT_FOUND, clipId + " is not a clip ID (s000000-c00)");
		}

		ReplayScene scene = ReplayScene.load(directory, clipId.substring(0, clipId.indexOf('-')));
		if (scene.manifest().kind() != DatasetKind.CLIPS) {
			throw new ReplayException(ReplayException.Reason.INVALID_DATASET, scene.manifest().datasetId() + " is not a clip dataset");
		}

		try {
			ClipRecord clip = null;
			for (JsonObject line : Jsonl.read(Files.readAllBytes(directory.resolve("clips.jsonl")), "clips.jsonl")) {
				if (clipId.equals(line.get("clip_id").getAsString())) {
					clip = ClipRecord.fromJson(line);
					break;
				}
			}

			if (clip == null) {
				throw new ReplayException(ReplayException.Reason.NOT_FOUND, "dataset " + scene.manifest().datasetId() + " has no clip " + clipId);
			}

			List<FrameRecord> frames = Jsonl.read(Files.readAllBytes(directory.resolve(clip.frames().path())), clip.frames().path()).stream().map(FrameRecord::fromJson).toList();
			if (frames.size() != clip.frameCount()) {
				throw new ReplayException(ReplayException.Reason.INVALID_DATASET, "clip " + clipId + " has " + frames.size() + " frame records for " + clip.frameCount() + " frames");
			}

			return new ReplayClip(scene, clip, frames);
		} catch (IOException e) {
			throw new ReplayException(ReplayException.Reason.INVALID_DATASET, "cannot read clip " + clipId + ": " + e.getMessage());
		} catch (ContractException e) {
			throw new ReplayException(ReplayException.Reason.INVALID_DATASET, "cannot read the records of clip " + clipId + ": " + e.getMessage());
		}
	}

	public ReplayScene scene() {
		return this.scene;
	}

	public ClipRecord clip() {
		return this.clip;
	}

	/** The stored frame records, in order. */
	public List<FrameRecord> frames() {
		return this.frames;
	}

	/** The teacher command recorded for tick {@code tick}, which runs beside the executed one. */
	public MotorCommand teacher(int tick) {
		return this.frames.get(tick * this.clip.framesPerTick()).tickLabels().teacher();
	}

	/**
	 * Compares the next replayed frame with the stored one.
	 *
	 * @param target the honey's absolute cell, or null without honey
	 */
	public void compare(CameraPose pose, double partialTick, CapturedImages images, int @Nullable [] target) {
		int index = this.compared++;
		if (index >= this.frames.size()) {
			note("frame " + index + " is beyond the clip's " + this.frames.size() + " frames");
			this.poseMismatches++;
			return;
		}

		FrameRecord stored = this.frames.get(index);
		FrameRecord fresh = ClipLabels.frame(stored.clipId(), index, stored.tick(), stored.subFrame(), stored.framesPerTick(),
			new ClipEnvironment.Frame(pose, partialTick, images), target, this.scene.projection(),
			this.scene.config().viewpoints().eyeHeightBlocks(), null);
		if (!samePose(stored.pose(), fresh.pose())) {
			this.poseMismatches++;
			note("frame " + index + ": pose " + fresh.pose() + " differs from the recorded " + stored.pose());
		}

		if (!SampleRecord.targetJson(stored.target()).equals(SampleRecord.targetJson(fresh.target())) || stored.touchesEdge() != fresh.touchesEdge()) {
			this.labelMismatches++;
			note("frame " + index + ": labels " + SampleRecord.targetJson(fresh.target()) + " differ from the recorded " + SampleRecord.targetJson(stored.target()));
		}

		try {
			byte[] storedMask = next(this.mask(), images.width() * images.height());
			if (!Arrays.equals(storedMask, images.visibleMask())) {
				this.maskMismatches++;
				note("frame " + index + ": " + differing(storedMask, images.visibleMask()) + " mask pixels differ");
			}

			if (this.rgbCompared) {
				byte[] storedRgb = next(this.rgb(), images.width() * images.height() * 3);
				if (!Arrays.equals(storedRgb, images.rgb())) {
					this.rgbMismatches++;
					note("frame " + index + ": " + differing(storedRgb, images.rgb()) + " RGB bytes differ");
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** The comparison so far; frames the replay never reached count as pose mismatches. */
	public Result result() {
		int missing = Math.max(0, this.frames.size() - this.compared);
		if (missing > 0) {
			note(missing + " recorded frames were not replayed");
		}

		return new Result(this.clip.clipId(), this.frames.size(), this.poseMismatches + missing, this.labelMismatches, this.maskMismatches, this.rgbMismatches, this.rgbCompared,
			List.copyOf(this.differences));
	}

	@Override
	public void close() {
		for (Process process : new Process[] {this.rgb, this.mask}) {
			if (process != null) {
				process.destroy();
			}
		}
	}

	private Process mask() throws IOException {
		if (this.mask == null) {
			this.mask = decode(this.clip.visibleMask(), "gray");
		}

		return this.mask;
	}

	private Process rgb() throws IOException {
		if (this.rgb == null) {
			this.rgb = decode(this.clip.rgb(), "rgb24");
		}

		return this.rgb;
	}

	private Process decode(ClipRecord.VideoFile video, String pixelFormat) throws IOException {
		return new ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-i", this.scene.file(video.path()).toString(), "-f", "rawvideo", "-pix_fmt", pixelFormat, "-")
			.redirectError(ProcessBuilder.Redirect.DISCARD).start();
	}

	private static byte[] next(Process decoder, int size) throws IOException {
		InputStream in = decoder.getInputStream();
		byte[] frame = in.readNBytes(size);
		if (frame.length != size) {
			throw new IOException("the stored video ended early");
		}

		return frame;
	}

	private static int differing(byte[] a, byte[] b) {
		int count = Math.abs(a.length - b.length);
		for (int i = 0; i < Math.min(a.length, b.length); i++) {
			count += a[i] != b[i] ? 1 : 0;
		}

		return count;
	}

	private static boolean samePose(CameraPose a, CameraPose b) {
		double yaw = Math.abs(((a.yawDegrees() - b.yawDegrees()) % 360.0 + 540.0) % 360.0 - 180.0);
		return Math.abs(a.x() - b.x()) <= POSE_TOLERANCE && Math.abs(a.y() - b.y()) <= POSE_TOLERANCE && Math.abs(a.z() - b.z()) <= POSE_TOLERANCE
			&& yaw <= POSE_TOLERANCE && Math.abs(a.pitchDegrees() - b.pitchDegrees()) <= POSE_TOLERANCE;
	}

	private void note(String difference) {
		if (this.differences.size() < LISTED) {
			this.differences.add(difference);
		}
	}
}
