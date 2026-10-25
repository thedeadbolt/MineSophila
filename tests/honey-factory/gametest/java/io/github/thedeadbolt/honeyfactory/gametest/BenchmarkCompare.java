package io.github.thedeadbolt.honeyfactory.gametest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import io.github.thedeadbolt.honeyfactory.image.PngImages;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Compares two outputs of {@link M12BenchmarkTest} against the M12-1 quality bar: masks
 * identical, RGB identical or within {@link #RGB_TOLERANCE} (level 5: see
 * {@link #LEVEL5_RGB_TOLERANCE}), and the same samples and ticks
 * on both sides. Writes {@code compare.json} beside the {@code after} output.
 */
final class BenchmarkCompare {
	/**
	 * The largest RGB difference a sample may show and still count as the same frame, measured
	 * by comparing two benchmark runs of one build (M12-1 task 1): the largest per-channel
	 * difference of any pixel and the largest fraction of pixels that differ at all.
	 */
	static final Tolerance RGB_TOLERANCE = new Tolerance(0, 0.0);
	/**
	 * The same for clear level-5 samples, which generate in a reloaded world snapshot whose
	 * lighting is not exactly repeatable. Two runs differed in at most 1.3% of a sample's
	 * pixels, by up to 125; any pixel may differ, in at most 2% of them. Rainy level-5 samples
	 * draw random rain streaks (up to 95% of pixels differed), so only their masks are compared.
	 */
	static final Tolerance LEVEL5_RGB_TOLERANCE = new Tolerance(255, 0.02);

	/** A per-sample RGB tolerance. */
	record Tolerance(int maxChannelDifference, double maxDifferingFraction) {
		boolean allows(Difference difference) {
			return difference.maxChannel() <= this.maxChannelDifference && difference.differingFraction() <= this.maxDifferingFraction;
		}
	}

	/** How two RGB images differ: the largest channel difference and the share of differing pixels. */
	record Difference(int maxChannel, double differingFraction) {
		static final Difference NONE = new Difference(0, 0.0);
	}

	/** The comparison: frames compared, identical RGB, the largest differences seen, and every failure. */
	record Result(int frames, int identicalRgb, int uncheckedRgb, int maxChannel, double maxDifferingFraction, List<String> failures) {
		boolean passed() {
			return this.failures.isEmpty();
		}
	}

	/** One frame's files and digests, and whether it shows rain. */
	private record Frame(Path rgb, String rgbSha256, Path mask, String maskSha256, boolean rain) {
	}

	private BenchmarkCompare() {
	}

	static Result compare(Path before, Path after) {
		return compare(before, after, RGB_TOLERANCE, LEVEL5_RGB_TOLERANCE);
	}

	static Result compare(Path before, Path after, Tolerance tolerance, Tolerance level5Tolerance) {
		Map<String, Frame> first = frames(before);
		Map<String, Frame> second = frames(after);
		List<String> failures = new ArrayList<>();
		for (String key : first.keySet()) {
			if (!second.containsKey(key)) {
				failures.add(key + ": missing after");
			}
		}

		for (String key : second.keySet()) {
			if (!first.containsKey(key)) {
				failures.add(key + ": not in before");
			}
		}

		int frames = 0;
		int identical = 0;
		int unchecked = 0;
		int maxChannel = 0;
		double maxFraction = 0.0;
		JsonArray differing = new JsonArray();
		for (Map.Entry<String, Frame> entry : first.entrySet()) {
			Frame b = entry.getValue();
			Frame a = second.get(entry.getKey());
			if (a == null) {
				continue;
			}

			frames++;
			if (!b.maskSha256().equals(a.maskSha256()) && !Arrays.equals(PngImages.decode(read(b.mask())).data(), PngImages.decode(read(a.mask())).data())) {
				failures.add(entry.getKey() + ": mask differs");
			}

			Difference difference = b.rgbSha256().equals(a.rgbSha256()) ? Difference.NONE : difference(read(b.rgb()), read(a.rgb()));
			if (difference.maxChannel() == 0) {
				identical++;
				continue;
			}

			boolean level5 = entry.getKey().startsWith("still/m12-benchmark-l5/");
			if (level5 && (b.rain() || a.rain())) {
				unchecked++;
				continue;
			}

			maxChannel = Math.max(maxChannel, difference.maxChannel());
			maxFraction = Math.max(maxFraction, difference.differingFraction());
			JsonObject row = new JsonObject();
			row.addProperty("frame", entry.getKey());
			row.addProperty("max_channel", difference.maxChannel());
			row.addProperty("differing_fraction", difference.differingFraction());
			differing.add(row);
			Tolerance allowed = level5 ? level5Tolerance : tolerance;
			if (!allowed.allows(difference)) {
				failures.add(entry.getKey() + ": RGB differs by up to " + difference.maxChannel() + " in " + difference.differingFraction() + " of pixels, beyond " + allowed);
			}
		}

		Result result = new Result(frames, identical, unchecked, maxChannel, maxFraction, List.copyOf(failures));
		JsonObject json = new JsonObject();
		json.addProperty("before", before.toString());
		json.addProperty("after", after.toString());
		json.addProperty("frames", frames);
		json.addProperty("identical_rgb", identical);
		json.addProperty("unchecked_rainy_level5_rgb", unchecked);
		json.addProperty("max_channel", maxChannel);
		json.addProperty("max_differing_fraction", maxFraction);
		json.addProperty("tolerance_max_channel", tolerance.maxChannelDifference());
		json.addProperty("tolerance_max_differing_fraction", tolerance.maxDifferingFraction());
		json.addProperty("level5_tolerance_max_channel", level5Tolerance.maxChannelDifference());
		json.addProperty("level5_tolerance_max_differing_fraction", level5Tolerance.maxDifferingFraction());
		json.add("differing_rgb", differing);
		JsonArray list = new JsonArray();
		failures.forEach(list::add);
		json.add("failures", list);
		try {
			Files.writeString(after.resolve("compare.json"), new GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		return result;
	}

	/** The largest channel difference and the share of pixels that differ in any channel. */
	static Difference difference(byte[] beforePng, byte[] afterPng) {
		PngImages.Pixels b = PngImages.decode(beforePng);
		PngImages.Pixels a = PngImages.decode(afterPng);
		if (b.width() != a.width() || b.height() != a.height() || b.channels() != a.channels()) {
			return new Difference(255, 1.0);
		}

		int max = 0;
		long differing = 0;
		byte[] x = b.data();
		byte[] y = a.data();
		for (int p = 0; p < x.length; p += b.channels()) {
			boolean differs = false;
			for (int c = 0; c < b.channels(); c++) {
				int d = Math.abs((x[p + c] & 0xFF) - (y[p + c] & 0xFF));
				if (d > 0) {
					differs = true;
					max = Math.max(max, d);
				}
			}

			if (differs) {
				differing++;
			}
		}

		return new Difference(max, differing / (double) (x.length / b.channels()));
	}

	/**
	 * Every frame of a benchmark output, keyed {@code still/<dataset name>/<sample id>} for
	 * dataset samples and {@code control/<tick>} for control-loop ticks. Dataset folders are
	 * matched by name without their configuration hash, so a version bump still compares.
	 */
	private static Map<String, Frame> frames(Path root) {
		Map<String, Frame> frames = new LinkedHashMap<>();
		Path still = root.resolve("still");
		if (Files.isDirectory(still)) {
			try (Stream<Path> datasets = Files.list(still)) {
				for (Path dataset : datasets.filter(path -> Files.isRegularFile(path.resolve("samples.jsonl"))).sorted().toList()) {
					String name = dataset.getFileName().toString().replaceFirst("-[0-9a-f]{12}$", "");
					for (String line : Files.readAllLines(dataset.resolve("samples.jsonl"), StandardCharsets.UTF_8)) {
						JsonObject sample = JsonFields.parseObject(line);
						JsonObject files = sample.getAsJsonObject("files");
						JsonObject rgb = files.getAsJsonObject("rgb");
						JsonObject mask = files.getAsJsonObject("mask");
						frames.put("still/" + name + "/" + sample.get("sample_id").getAsString(), new Frame(dataset.resolve(rgb.get("path").getAsString()), rgb.get("sha256").getAsString(),
							dataset.resolve(mask.get("path").getAsString()), mask.get("sha256").getAsString(), !"clear".equals(sample.getAsJsonObject("conditions").get("weather").getAsString())));
					}
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		Path ticks = root.resolve("control/ticks.jsonl");
		if (Files.isRegularFile(ticks)) {
			try {
				for (String line : Files.readAllLines(ticks, StandardCharsets.UTF_8)) {
					JsonObject tick = JsonFields.parseObject(line);
					String frame = tick.get("frame").getAsString();
					frames.put("control/" + frame, new Frame(root.resolve("control").resolve(frame + "-rgb.png"), tick.get("rgb_sha256").getAsString(),
						root.resolve("control").resolve(frame + "-mask.png"), tick.get("mask_sha256").getAsString(), false));
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		return frames;
	}

	private static byte[] read(Path file) {
		try {
			return Files.readAllBytes(file);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
