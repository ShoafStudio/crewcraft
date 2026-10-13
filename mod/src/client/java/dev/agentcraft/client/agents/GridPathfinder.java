package dev.agentcraft.client.agents;

import dev.agentcraft.layout.Anchors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * A* over the client level's blocks for agent walking (no server AI involved). A cell (x,y,z) is
 * walkable when the block below has a solid top, the feet block is passable or a thin floor layer
 * (carpet/strip, at most 1/8 block high) and the head block is passable. Moves: 8 directions (no
 * corner cutting) and 1-block steps up/down. The raw cell path is shortened by line-of-sight
 * string pulling, so agents walk straight lines between corners instead of zig-zagging.
 *
 * <p>Deterministic: same world + same endpoints = same path. Search is limited to the layout's
 * bounds (padded) and a node budget, so it never stalls the client tick (typically &lt; 1 ms).
 */
public final class GridPathfinder {
	private static final int MAX_NODES = 12_000;
	private static final double THIN = 0.125;
	private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

	private final BlockGetter level;
	private final Anchors.@Nullable Bounds bounds;
	private final Map<Long, Double> floorCache = new HashMap<>();

	public GridPathfinder(BlockGetter level, Anchors.@Nullable Bounds bounds) {
		this.level = level;
		this.bounds = bounds;
	}

	/** Feet height of a walkable cell, or NaN when an agent cannot stand there. */
	public double floor(int x, int y, int z) {
		long key = BlockPos.asLong(x, y, z);
		Double cached = floorCache.get(key);
		if (cached != null) {
			return cached;
		}
		double v = computeFloor(x, y, z);
		floorCache.put(key, v);
		return v;
	}

	private double computeFloor(int x, int y, int z) {
		if (bounds != null && !inPadded(x, y, z)) {
			return Double.NaN;
		}
		BlockPos below = new BlockPos(x, y - 1, z);
		BlockPos feet = new BlockPos(x, y, z);
		BlockPos head = new BlockPos(x, y + 1, z);
		double feetTop = top(feet);
		if (Double.isNaN(feetTop)) {
			feetTop = 0;
		} else if (feetTop > THIN) {
			return Double.NaN; // something solid where the feet go
		}
		if (!Double.isNaN(top(head))) {
			return Double.NaN; // no headroom
		}
		if (feetTop == 0) {
			double belowTop = top(below);
			if (Double.isNaN(belowTop) || belowTop < 0.9) {
				return Double.NaN; // nothing to stand on
			}
		}
		return y + feetTop;
	}

	private boolean inPadded(int x, int y, int z) {
		Anchors.Bounds b = bounds;
		return x >= b.minX() - 2 && x <= b.maxX() + 2 && y >= b.minY() - 2 && y <= b.maxY() + 2 && z >= b.minZ() - 2 && z <= b.maxZ() + 2;
	}

	/** Max Y (0..1.5) of the block's collision shape at pos, NaN when it has none. */
	private double top(BlockPos pos) {
		BlockState s = level.getBlockState(pos);
		if (s.isAir()) {
			return Double.NaN;
		}
		VoxelShape shape = s.getCollisionShape(level, pos);
		return shape.isEmpty() ? Double.NaN : shape.max(net.minecraft.core.Direction.Axis.Y);
	}

	/** The walkable cell an agent at {@code p} stands in (searches 1 block up/down), or null. */
	public @Nullable BlockPos cellAt(Vec3 p) {
		int x = (int) Math.floor(p.x);
		int z = (int) Math.floor(p.z);
		int y = (int) Math.floor(p.y + 0.01);
		for (int dy : new int[] {0, 1, -1}) {
			if (!Double.isNaN(floor(x, y + dy, z))) {
				return new BlockPos(x, y + dy, z);
			}
		}
		return null;
	}

	private record Node(int x, int y, int z, double g, double f, @Nullable Node parent) {
	}

	/**
	 * Path from {@code from} to {@code to} as feet positions (first = from, last = to exactly), or null
	 * when there is none within the budget. A trivial path ({@code from} == {@code to}) has 2 points.
	 */
	public @Nullable List<Vec3> find(Vec3 from, Vec3 to) {
		BlockPos start = cellAt(from);
		BlockPos goal = cellAt(to);
		if (start == null || goal == null) {
			return null;
		}
		if (start.equals(goal)) {
			return List.of(from, to);
		}
		PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> a.f != b.f ? Double.compare(a.f, b.f) : Double.compare(b.g, a.g));
		Map<Long, Double> best = new HashMap<>();
		open.add(new Node(start.getX(), start.getY(), start.getZ(), 0, h(start.getX(), start.getY(), start.getZ(), goal), null));
		best.put(start.asLong(), 0.0);
		int expanded = 0;
		Node found = null;
		while (!open.isEmpty() && expanded < MAX_NODES) {
			Node n = open.poll();
			if (n.g > best.getOrDefault(BlockPos.asLong(n.x, n.y, n.z), Double.MAX_VALUE) + 1e-9) {
				continue;
			}
			if (n.x == goal.getX() && n.y == goal.getY() && n.z == goal.getZ()) {
				found = n;
				break;
			}
			expanded++;
			for (int[] d : DIRS) {
				boolean diagonal = d[0] != 0 && d[1] != 0;
				for (int dy : new int[] {0, 1, -1}) {
					int nx = n.x + d[0];
					int ny = n.y + dy;
					int nz = n.z + d[1];
					if (Double.isNaN(floor(nx, ny, nz))) {
						continue;
					}
					if (dy != 0 && diagonal) {
						continue; // steps only straight on
					}
					if (dy == 1 && !Double.isNaN(top(new BlockPos(n.x, n.y + 2, n.z)))) {
						continue; // no headroom to step up
					}
					if (diagonal && (Double.isNaN(floor(n.x + d[0], n.y, n.z)) || Double.isNaN(floor(n.x, n.y, n.z + d[1])))) {
						continue; // never cut corners
					}
					double cost = (diagonal ? 1.41421356 : 1.0) + (dy != 0 ? 0.6 : 0);
					double g = n.g + cost;
					long key = BlockPos.asLong(nx, ny, nz);
					if (g < best.getOrDefault(key, Double.MAX_VALUE) - 1e-9) {
						best.put(key, g);
						open.add(new Node(nx, ny, nz, g, g + h(nx, ny, nz, goal), n));
					}
				}
			}
		}
		if (found == null) {
			return null;
		}
		List<BlockPos> cells = new ArrayList<>();
		for (Node n = found; n != null; n = n.parent) {
			cells.add(new BlockPos(n.x, n.y, n.z));
		}
		Collections.reverse(cells);
		return smooth(from, to, cells);
	}

	private static double h(int x, int y, int z, BlockPos goal) {
		int dx = Math.abs(x - goal.getX());
		int dz = Math.abs(z - goal.getZ());
		return Math.max(dx, dz) + 0.41421356 * Math.min(dx, dz) + Math.abs(y - goal.getY()) * 0.6;
	}

	/** String pulling: keep only the corners where the straight line would leave walkable ground. */
	private List<Vec3> smooth(Vec3 from, Vec3 to, List<BlockPos> cells) {
		List<Vec3> pts = new ArrayList<>(cells.size());
		pts.add(from);
		for (int i = 1; i < cells.size() - 1; i++) {
			BlockPos c = cells.get(i);
			pts.add(new Vec3(c.getX() + 0.5, floor(c.getX(), c.getY(), c.getZ()), c.getZ() + 0.5));
		}
		pts.add(to);
		List<Vec3> out = new ArrayList<>();
		out.add(pts.getFirst());
		int i = 0;
		while (i < pts.size() - 1) {
			int j = pts.size() - 1;
			while (j > i + 1 && !clear(pts.get(i), pts.get(j))) {
				j--;
			}
			out.add(pts.get(j));
			i = j;
		}
		return out;
	}

	/** Can an agent (0.6 wide) walk the straight segment a->b on one level? */
	public boolean clear(Vec3 a, Vec3 b) {
		if (Math.abs(a.y - b.y) > 0.2) {
			return false;
		}
		double len = Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z));
		int steps = Math.max(1, (int) Math.ceil(len / 0.2));
		int y = (int) Math.floor(a.y + 0.01);
		double nx = len == 0 ? 0 : -(b.z - a.z) / len * 0.3;
		double nz = len == 0 ? 0 : (b.x - a.x) / len * 0.3;
		for (int s = 0; s <= steps; s++) {
			double t = (double) s / steps;
			double px = a.x + (b.x - a.x) * t;
			double pz = a.z + (b.z - a.z) * t;
			if (Double.isNaN(floor((int) Math.floor(px), y, (int) Math.floor(pz)))
				|| Double.isNaN(floor((int) Math.floor(px + nx), y, (int) Math.floor(pz + nz)))
				|| Double.isNaN(floor((int) Math.floor(px - nx), y, (int) Math.floor(pz - nz)))) {
				return false;
			}
		}
		return true;
	}
}
