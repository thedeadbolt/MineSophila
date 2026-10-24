package io.github.thedeadbolt.honeyfactory.scene;

import io.github.thedeadbolt.honeyfactory.contract.Sha256;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Where level-5 source patches come from ({@value #ALGORITHM}). Every patch index has up
 * to {@value #DRAWS} candidate places, each in its own cell of a grid over the source
 * world, so no two candidates of a dataset ever share a cell.
 *
 * <ol>
 *   <li>Cells are {@code W + S} by {@code L + S} blocks, where {@code W} and {@code L} are
 *       the copied width and length and {@code S} the region separation. The grid has
 *       {@value #GRID} × {@value #GRID} cells, cell {@code (i, j)} for {@code i, j} in
 *       {@code -500 … 500} starting at block {@code (i (W + S), j (L + S))}.</li>
 *   <li>Candidate {@code d} of patch {@code p} is number {@code n = p · DRAWS + d}. With
 *       {@code M = GRID²}, {@code A = 1000003} and {@code B} the first 8 bytes, big-endian,
 *       of {@code SHA-256("honey-factory/source-patches-v1/offset/<dataset seed>")} taken
 *       modulo {@code M}, it lies in cell number {@code m = (n A + B) mod M}, that is
 *       {@code i = m mod GRID − 500} and {@code j = ⌊m / GRID⌋ − 500}. As {@code A} is prime
 *       to {@code M}, distinct candidates get distinct cells.</li>
 *   <li>The copied region starts {@code 16 ⌊S / 32⌋} blocks into its cell on both axes, so
 *       regions of different cells are at least {@code S} blocks apart.</li>
 *   <li>Its vertical window ends at the first section boundary at least {@value #HEADROOM}
 *       blocks above the highest non-air block of the region and spans the configured
 *       height, kept inside the world's build limits ({@link #window}).</li>
 * </ol>
 */
public final class SourcePatches {
	public static final String ALGORITHM = "source-patches-v1";
	public static final int DRAWS = 32;
	public static final int GRID = 1001;
	public static final int HEADROOM = 8;
	private static final long CELLS = (long) GRID * GRID;
	private static final long MULTIPLIER = 1_000_003L;

	private SourcePatches() {
	}

	/** The most patches a dataset can have. */
	public static int maxPatches() {
		return (int) (CELLS / DRAWS);
	}

	/** The minimum x and z of candidate {@code draw} of patch {@code patchIndex}. */
	public static int[] corner(long datasetSeed, SceneBase.NaturalTerrain base, int patchIndex, int draw) {
		if (patchIndex < 0 || patchIndex >= maxPatches() || draw < 0 || draw >= DRAWS) {
			throw new IllegalArgumentException("Patch " + patchIndex + " draw " + draw + " is out of range");
		}

		long offset = Math.floorMod(ByteBuffer.wrap(Sha256.digest(("honey-factory/" + ALGORITHM + "/offset/" + datasetSeed).getBytes(StandardCharsets.UTF_8))).getLong(), CELLS);
		long cell = Math.floorMod(((long) patchIndex * DRAWS + draw) * MULTIPLIER + offset, CELLS);
		int i = (int) (cell % GRID) - GRID / 2;
		int j = (int) (cell / GRID) - GRID / 2;
		int inset = 16 * (base.regionSeparationBlocks() / 32);
		return new int[] {
			i * (base.copyWidth() + base.regionSeparationBlocks()) + inset,
			j * (base.copyLength() + base.regionSeparationBlocks()) + inset,
		};
	}

	/**
	 * The lowest y of a {@code height}-block window whose top lies on the first section
	 * boundary at least {@value #HEADROOM} blocks above {@code highestBlock}, lowered to the
	 * build limit {@code maxY} and raised to {@code minY} when it would leave the world.
	 */
	public static int window(int highestBlock, int height, int minY, int maxY) {
		int top = Math.min(Math.ceilDiv(highestBlock + 1 + HEADROOM, 16) * 16, maxY + 1);
		return Math.max(minY, top - height);
	}
}
