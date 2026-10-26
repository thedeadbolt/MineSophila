package io.github.thedeadbolt.honeyfactory.teacher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import org.jspecify.annotations.Nullable;

/** A* over a {@link WalkGraph}, with the octile distance as its estimate. */
public final class PathPlanner {
	/** Cells expanded before a search gives up, more than any arena holds on one floor. */
	static final int MAX_EXPANSIONS = 40_000;

	private PathPlanner() {
	}

	private record Node(int[] cell, double cost, double estimate) {
	}

	/** The cells from {@code from} to {@code goal}, both included, or null when none is known. */
	public static @Nullable List<int[]> path(WalkGraph graph, int[] from, int[] goal) {
		long target = SeenMap.key(goal[0], goal[1], goal[2]);
		Map<Long, Double> costs = new HashMap<>();
		Map<Long, int[]> previous = new HashMap<>();
		PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.estimate(), b.estimate()));
		costs.put(SeenMap.key(from[0], from[1], from[2]), 0.0);
		open.add(new Node(from, 0.0, octile(from, goal)));
		int expanded = 0;
		while (!open.isEmpty() && expanded < MAX_EXPANSIONS) {
			Node node = open.poll();
			long key = SeenMap.key(node.cell()[0], node.cell()[1], node.cell()[2]);
			if (node.cost() > costs.getOrDefault(key, Double.POSITIVE_INFINITY)) {
				continue;
			}

			if (key == target) {
				return trace(previous, node.cell(), from);
			}

			expanded++;
			for (WalkGraph.Edge edge : graph.edges(node.cell())) {
				int[] next = edge.to();
				long nextKey = SeenMap.key(next[0], next[1], next[2]);
				double cost = node.cost() + edge.cost();
				if (cost < costs.getOrDefault(nextKey, Double.POSITIVE_INFINITY)) {
					costs.put(nextKey, cost);
					previous.put(nextKey, node.cell());
					open.add(new Node(next, cost, cost + octile(next, goal)));
				}
			}
		}

		return null;
	}

	/** The octile distance between two cells' columns, a lower bound on any path between them. */
	static double octile(int[] a, int[] b) {
		int dx = Math.abs(a[0] - b[0]);
		int dz = Math.abs(a[2] - b[2]);
		return Math.max(dx, dz) + (Math.sqrt(2.0) - 1.0) * Math.min(dx, dz);
	}

	private static List<int[]> trace(Map<Long, int[]> previous, int[] goal, int[] from) {
		List<int[]> path = new ArrayList<>();
		int[] cell = goal;
		while (cell != null) {
			path.add(cell);
			if (cell[0] == from[0] && cell[1] == from[1] && cell[2] == from[2]) {
				break;
			}

			cell = previous.get(SeenMap.key(cell[0], cell[1], cell[2]));
		}

		Collections.reverse(path);
		return path;
	}
}
