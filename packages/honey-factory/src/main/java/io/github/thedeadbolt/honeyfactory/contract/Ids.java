package io.github.thedeadbolt.honeyfactory.contract;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Identifier, shard and relative-path rules ({@value #ALGORITHM}).
 *
 * <ul>
 *   <li>Dataset: {@code <name>-<first 12 hex of the resolved-configuration SHA-256>}.</li>
 *   <li>Scene: {@code s<scene index, 6 digits>}, unique within a dataset.</li>
 *   <li>Sample: {@code <scene id>-v<viewpoint index, 2 digits>}.</li>
 *   <li>Shard: first 2 hex characters of the SHA-256 of the UTF-8 sample ID.</li>
 *   <li>Images: {@code rgb|masks/<split>/<shard>/<sample id>.png}.</li>
 * </ul>
 */
public final class Ids {
	public static final String ALGORITHM = "ids-v1";
	public static final Pattern DATASET_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,47}");
	public static final Pattern DATASET_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,47}-[0-9a-f]{12}");
	public static final Pattern SCENE_ID = Pattern.compile("s[0-9]{6}");
	public static final Pattern SAMPLE_ID = Pattern.compile("s[0-9]{6}-v[0-9]{2}");
	public static final int MAX_SCENES = 999_999;
	public static final int MAX_VIEWPOINTS = 99;

	private static final int CONFIG_HASH_PREFIX = 12;
	private static final int SHARD_LENGTH = 2;

	private Ids() {
	}

	public static boolean isDatasetName(String name) {
		return DATASET_NAME.matcher(name).matches();
	}

	public static String datasetId(String name, String configSha256) {
		if (!isDatasetName(name) || !Sha256.isHex(configSha256)) {
			throw new IllegalArgumentException("Invalid dataset name or configuration hash");
		}

		return name + "-" + configSha256.substring(0, CONFIG_HASH_PREFIX);
	}

	public static String sceneId(int sceneIndex) {
		if (sceneIndex < 0 || sceneIndex >= MAX_SCENES) {
			throw new IllegalArgumentException("Scene index out of range: " + sceneIndex);
		}

		return String.format("s%06d", sceneIndex);
	}

	public static String sampleId(String sceneId, int viewpointIndex) {
		if (!SCENE_ID.matcher(sceneId).matches() || viewpointIndex < 0 || viewpointIndex >= MAX_VIEWPOINTS) {
			throw new IllegalArgumentException("Invalid scene ID or viewpoint index");
		}

		return String.format("%s-v%02d", sceneId, viewpointIndex);
	}

	public static String shard(String sampleId) {
		return Sha256.hex(sampleId.getBytes(StandardCharsets.UTF_8)).substring(0, SHARD_LENGTH);
	}

	public static String rgbPath(Split split, String sampleId) {
		return imagePath("rgb", split, sampleId);
	}

	public static String maskPath(Split split, String sampleId) {
		return imagePath("masks", split, sampleId);
	}

	/** Dataset-root-relative, {@code /}-separated, with no empty, {@code .} or {@code ..} segments. */
	public static boolean isRelativePath(String path) {
		if (path.isEmpty() || path.startsWith("/") || path.contains("\\")) {
			return false;
		}

		for (String segment : path.split("/", -1)) {
			if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
				return false;
			}
		}

		return true;
	}

	private static String imagePath(String root, Split split, String sampleId) {
		return root + "/" + split.jsonName() + "/" + shard(sampleId) + "/" + sampleId + ".png";
	}
}
