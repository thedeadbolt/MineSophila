package io.github.thedeadbolt.honeyfactory.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PanoramaSamplingTest {
	@Test
	void workerThreadsReprojectExactlyAsOneThreadDoes() {
		PanoramicProjection projection = new PanoramicProjection(2480, 1280, 1024);
		Random random = new Random(12);
		List<CapturedImages> faces = new ArrayList<>();
		for (int f = 0; f < CubeFace.values().length; f++) {
			byte[] rgb = new byte[1024 * 1024 * 3];
			byte[] visible = new byte[1024 * 1024];
			byte[] unoccluded = new byte[1024 * 1024];
			random.nextBytes(rgb);
			for (int p = 0; p < visible.length; p++) {
				visible[p] = random.nextInt(4) == 0 ? (byte) 255 : 0;
				unoccluded[p] = random.nextInt(3) == 0 ? (byte) 255 : 0;
			}

			faces.add(new CapturedImages(1024, 1024, rgb, visible, unoccluded));
		}

		PanoramaSampling sampling = PanoramaSampling.of(projection);
		assertTrue(PanoramaSampling.THREADS > 1, "the shared pool has more than one thread on this machine");
		CapturedImages sequential = sampling.reprojectSequential(faces);
		CapturedImages parallel = sampling.reproject(faces);
		assertArrayEquals(sequential.rgb(), parallel.rgb());
		assertArrayEquals(sequential.visibleMask(), parallel.visibleMask());
		assertArrayEquals(sequential.unoccludedMask(), parallel.unoccludedMask());
	}
}
