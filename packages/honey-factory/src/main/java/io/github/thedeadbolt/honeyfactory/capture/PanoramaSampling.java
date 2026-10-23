package io.github.thedeadbolt.honeyfactory.capture;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Where each panorama pixel samples the cube faces. The panorama is level, so the table
 * depends only on the output and face sizes, never on the camera, and one table serves
 * every capture of a dataset.
 *
 * <p>For each output pixel centre the table holds the face whose forward axis is closest
 * to the pixel's direction (ties go to the earlier face in {@link CubeFace} order) and the
 * continuous face coordinates {@code (fx, fy)} of that direction, quantized to 1/256 pixel:
 * {@code q = round(256 f)}. From the quantized coordinates:
 *
 * <ul>
 *   <li>masks take the face pixel containing the point, {@code (q >> 8)} clamped to the
 *       face, so labels stay exactly 0 or 255;</li>
 *   <li>RGB is bilinear between the four nearest pixel centres with 8-bit weights,
 *       {@code s = q - 128}, {@code i = s >> 8}, {@code w = s & 255}, clamped at the face
 *       border, rounded half up.</li>
 * </ul>
 */
final class PanoramaSampling {
	private static final int SUBPIXEL_BITS = 8;
	private static final int COORDINATE_BITS = 22;
	private static final long COORDINATE_MASK = (1L << COORDINATE_BITS) - 1;
	/** The most recently built table; datasets use one size throughout. */
	private static volatile @Nullable PanoramaSampling cached;

	private final int width;
	private final int height;
	private final int faceSize;
	/** Per output pixel: face ordinal, then quantized fx and fy, 22 bits each. */
	private final long[] samples;

	private PanoramaSampling(int width, int height, int faceSize, long[] samples) {
		this.width = width;
		this.height = height;
		this.faceSize = faceSize;
		this.samples = samples;
	}

	static PanoramaSampling of(PanoramicProjection projection) {
		PanoramaSampling table = cached;
		if (table == null || table.width != projection.width() || table.height != projection.height() || table.faceSize != projection.faceSize()) {
			table = build(projection);
			cached = table;
		}

		return table;
	}

	private static PanoramaSampling build(PanoramicProjection projection) {
		int width = projection.width();
		int height = projection.height();
		int size = projection.faceSize();
		double[] sinLongitude = new double[width];
		double[] cosLongitude = new double[width];
		for (int x = 0; x < width; x++) {
			double longitude = StrictMath.toRadians(projection.longitudeDegrees(x + 0.5));
			sinLongitude[x] = StrictMath.sin(longitude);
			cosLongitude[x] = StrictMath.cos(longitude);
		}

		CubeFace[] faces = CubeFace.values();
		long[] samples = new long[width * height];
		double[] direction = new double[3];
		double half = size / 2.0;
		long limit = (long) size << SUBPIXEL_BITS;
		for (int y = 0; y < height; y++) {
			double elevation = StrictMath.toRadians(projection.elevationDegrees(y + 0.5));
			double sinElevation = StrictMath.sin(elevation);
			double cosElevation = StrictMath.cos(elevation);
			for (int x = 0; x < width; x++) {
				direction[0] = cosElevation * sinLongitude[x];
				direction[1] = sinElevation;
				direction[2] = cosElevation * cosLongitude[x];
				CubeFace face = faces[0];
				double best = face.dotForward(direction);
				for (int f = 1; f < faces.length; f++) {
					double along = faces[f].dotForward(direction);
					if (along > best) {
						best = along;
						face = faces[f];
					}
				}

				double fx = half * (1.0 + face.dotRight(direction) / best);
				double fy = half * (1.0 - face.dotUp(direction) / best);
				long qx = Math.clamp(Math.round(fx * (1 << SUBPIXEL_BITS)), 0L, limit);
				long qy = Math.clamp(Math.round(fy * (1 << SUBPIXEL_BITS)), 0L, limit);
				samples[y * width + x] = (long) face.ordinal() << (2 * COORDINATE_BITS) | qx << COORDINATE_BITS | qy;
			}
		}

		return new PanoramaSampling(width, height, size, samples);
	}

	/** Reprojects six face captures, in {@link CubeFace} order, into one panorama. */
	CapturedImages reproject(List<CapturedImages> faces) {
		int size = this.faceSize;
		int count = CubeFace.values().length;
		if (faces.size() != count) {
			throw new IllegalArgumentException("A panorama needs " + count + " faces, got " + faces.size());
		}

		byte[][] rgb = new byte[count][];
		byte[][] visible = new byte[count][];
		byte[][] unoccluded = new byte[count][];
		for (int f = 0; f < count; f++) {
			CapturedImages face = faces.get(f);
			if (face.width() != size || face.height() != size) {
				throw new IllegalArgumentException("Face " + CubeFace.values()[f] + " is " + face.width() + "x" + face.height() + ", expected " + size + "x" + size);
			}

			rgb[f] = face.rgb();
			visible[f] = face.visibleMask();
			unoccluded[f] = face.unoccludedMask();
		}

		int pixels = this.width * this.height;
		byte[] outRgb = new byte[pixels * 3];
		byte[] outVisible = new byte[pixels];
		byte[] outUnoccluded = new byte[pixels];
		int last = size - 1;
		for (int p = 0; p < pixels; p++) {
			long sample = this.samples[p];
			int face = (int) (sample >>> (2 * COORDINATE_BITS));
			int qx = (int) (sample >>> COORDINATE_BITS & COORDINATE_MASK);
			int qy = (int) (sample & COORDINATE_MASK);

			int nearest = Math.min(qy >> SUBPIXEL_BITS, last) * size + Math.min(qx >> SUBPIXEL_BITS, last);
			outVisible[p] = visible[face][nearest];
			outUnoccluded[p] = unoccluded[face][nearest];

			int sx = qx - 128;
			int sy = qy - 128;
			int x0 = sx >> SUBPIXEL_BITS;
			int y0 = sy >> SUBPIXEL_BITS;
			int wx = sx & 255;
			int wy = sy & 255;
			if (x0 < 0) {
				x0 = 0;
				wx = 0;
			} else if (x0 >= last) {
				x0 = last;
				wx = 0;
			}

			if (y0 < 0) {
				y0 = 0;
				wy = 0;
			} else if (y0 >= last) {
				y0 = last;
				wy = 0;
			}

			int x1 = Math.min(x0 + 1, last);
			int y1 = Math.min(y0 + 1, last);
			int w00 = (256 - wx) * (256 - wy);
			int w10 = wx * (256 - wy);
			int w01 = (256 - wx) * wy;
			int w11 = wx * wy;
			byte[] source = rgb[face];
			int i00 = (y0 * size + x0) * 3;
			int i10 = (y0 * size + x1) * 3;
			int i01 = (y1 * size + x0) * 3;
			int i11 = (y1 * size + x1) * 3;
			for (int c = 0; c < 3; c++) {
				int value = (source[i00 + c] & 0xFF) * w00 + (source[i10 + c] & 0xFF) * w10 + (source[i01 + c] & 0xFF) * w01 + (source[i11 + c] & 0xFF) * w11;
				outRgb[p * 3 + c] = (byte) ((value + 32_768) >>> 16);
			}
		}

		return new CapturedImages(this.width, this.height, outRgb, outVisible, outUnoccluded);
	}

	/** The face and quantized face coordinates output pixel {@code (x, y)} samples, for tests. */
	int[] sample(int x, int y) {
		long sample = this.samples[y * this.width + x];
		return new int[] {(int) (sample >>> (2 * COORDINATE_BITS)), (int) (sample >>> COORDINATE_BITS & COORDINATE_MASK), (int) (sample & COORDINATE_MASK)};
	}
}
