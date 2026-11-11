/*
 * Copyright 2026 Asymetryk
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.asymetrik.modular.report;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SortedMap;

import org.asymetrik.modular.report.ModuleGraph.Edge;

/**
 * Where the boxes and the arrows of the diagram go.
 *
 * <p>The modules are put in layers: a module sits below every module that uses it. Inside a cycle this is not
 * possible for every arrow, so some arrows of a cycle point up; an arrow outside a cycle always points down.
 * In a layer, the modules are ordered to limit the crossings: by the average position of their neighbours, then
 * by swapping two modules that are side by side when this removes crossings. When crossings remain, other
 * starting orders are tried. An arrow that passes several layers goes between the boxes of the layers it crosses.
 *
 * <p>The same modules and dependencies always give the same layout.
 */
final class DiagramLayout {

	/** A point of the diagram, in pixels from its top left corner. */
	record Point(double x, double y) {}

	/**
	 * The box of a module.
	 *
	 * @param name the module
	 * @param types its number of types, which decides the height of the box
	 * @param layer 0 for the top row
	 * @param x the left side
	 * @param y the top side
	 * @param width the width
	 * @param height the height
	 */
	record Box(String name, int types, int layer, double x, double y, double width, double height) {}

	/**
	 * The arrow of a dependency.
	 *
	 * @param edge the dependency
	 * @param points where it goes, from the side of the box of the module that uses to the side of the box of
	 *     the module that is used. Between two points with the same {@code x} it is a vertical line, else a curve
	 *     that leaves and arrives vertically
	 */
	record Arrow(Edge edge, List<Point> points) {

		boolean pointsDown() {
			return points.get(0).y() < points.get(points.size() - 1).y();
		}
	}

	static final double MARGIN = 16;
	private static final double COLUMN_GAP = 32;
	private static final double LAYER_GAP = 76;
	private static final double MIN_BOX_WIDTH = 104;
	private static final double MIN_BOX_HEIGHT = 46;
	private static final double EXTRA_BOX_HEIGHT = 38;
	private static final double CHARACTER_WIDTH = 8;
	private static final double PORT_SPACING = 20;
	private static final double PASS_WIDTH = 14;
	/** The straight part of an arrow next to a box, where its head is drawn. */
	static final double STRAIGHT_END = 16;
	/** The other starting orders tried when crossings remain, in a diagram small enough for it. */
	private static final int MORE_STARTS = 8;
	private static final int SLOTS_FOR_MORE_STARTS = 80;
	private static final int SLOTS_FOR_SWAPS = 400;

	private final List<Box> boxes;
	private final List<Arrow> arrows;
	private final double width;
	private final double height;

	private DiagramLayout(List<Box> boxes, List<Arrow> arrows, double width, double height) {
		this.boxes = boxes;
		this.arrows = arrows;
		this.width = width;
		this.height = height;
	}

	/**
	 * @param typesByModule the modules, with their number of types
	 * @param edges the dependencies between these modules
	 */
	static DiagramLayout of(SortedMap<String, Integer> typesByModule, List<Edge> edges) {
		return new Work(typesByModule, edges).layout();
	}

	/** In the order of the module names. */
	List<Box> boxes() {
		return boxes;
	}

	/** In the order of the edges. */
	List<Arrow> arrows() {
		return arrows;
	}

	double width() {
		return width;
	}

	double height() {
		return height;
	}

	/**
	 * The computation. A slot is a place in a layer: the slots {@code 0} to {@code n - 1} are the modules, in
	 * the order of their names, and the next ones are the places where an arrow passes a layer.
	 */
	private static final class Work {
		private final List<String> names;
		private final List<Integer> types;
		private final List<Edge> edges;
		private final int[] from;
		private final int[] to;
		/** An arrow that closes a cycle: it is laid out as if it went the other way, so it points up. */
		private final boolean[] turned;
		private final List<List<Integer>> outgoing = new ArrayList<>();
		private final List<Integer> slotLayer = new ArrayList<>();
		private final List<Double> slotWidth = new ArrayList<>();
		private final List<List<Integer>> above = new ArrayList<>();
		private final List<List<Integer>> below = new ArrayList<>();
		/** For each edge, its slots from the top one to the bottom one. */
		private final List<List<Integer>> chains = new ArrayList<>();
		private List<List<Integer>> layers = new ArrayList<>();
		private double[] center;

		Work(SortedMap<String, Integer> typesByModule, List<Edge> edges) {
			this.names = new ArrayList<>(typesByModule.keySet());
			this.types = new ArrayList<>(typesByModule.values());
			this.edges = edges;
			Map<String, Integer> index = new HashMap<>();
			for (int i = 0; i < names.size(); i++) {
				index.put(names.get(i), i);
				outgoing.add(new ArrayList<>());
			}
			from = new int[edges.size()];
			to = new int[edges.size()];
			turned = new boolean[edges.size()];
			for (int e = 0; e < edges.size(); e++) {
				from[e] = index.get(edges.get(e).from());
				to[e] = index.get(edges.get(e).to());
				outgoing.get(from[e]).add(e);
			}
		}

		DiagramLayout layout() {
			int modules = names.size();
			if (modules == 0) {
				return new DiagramLayout(List.of(), List.of(), 0, 0);
			}
			turnCycleClosingEdges();
			int[] layerOfModule = assignLayers();
			buildSlots(layerOfModule);
			orderLayers();
			placeHorizontally();

			double[] boxHeight = new double[modules];
			int mostTypes = Math.max(1, Collections.max(types));
			for (int i = 0; i < modules; i++) {
				boxHeight[i] = MIN_BOX_HEIGHT + Math.round(EXTRA_BOX_HEIGHT * Math.sqrt(types.get(i) / (double) mostTypes));
			}
			double[] layerTop = new double[layers.size()];
			double[] layerHeight = new double[layers.size()];
			for (int i = 0; i < modules; i++) {
				layerHeight[layerOfModule[i]] = Math.max(layerHeight[layerOfModule[i]], boxHeight[i]);
			}
			double y = MARGIN;
			for (int l = 0; l < layers.size(); l++) {
				layerTop[l] = y;
				y += layerHeight[l] + LAYER_GAP;
			}
			double totalHeight = y - LAYER_GAP + MARGIN;

			double left = Double.MAX_VALUE;
			double right = -Double.MAX_VALUE;
			for (int slot = 0; slot < center.length; slot++) {
				left = Math.min(left, center[slot] - slotWidth.get(slot) / 2);
				right = Math.max(right, center[slot] + slotWidth.get(slot) / 2);
			}
			for (int slot = 0; slot < center.length; slot++) {
				center[slot] = round(center[slot] - left + MARGIN);
			}
			double totalWidth = round(right - left + 2 * MARGIN);

			List<Box> boxes = new ArrayList<>();
			for (int i = 0; i < modules; i++) {
				int layer = layerOfModule[i];
				boxes.add(new Box(names.get(i), types.get(i), layer, round(center[i] - slotWidth.get(i) / 2),
					round(layerTop[layer] + (layerHeight[layer] - boxHeight[i]) / 2), slotWidth.get(i), boxHeight[i]));
			}
			return new DiagramLayout(List.copyOf(boxes), List.copyOf(routeArrows(boxes, layerTop, layerHeight)),
				totalWidth, totalHeight);
		}

		/**
		 * Walks the dependencies from the modules that nobody uses, then from the others, always in the order
		 * of the names. An edge that comes back to a module being walked closes a cycle.
		 */
		private void turnCycleClosingEdges() {
			int modules = names.size();
			boolean[] used = new boolean[modules];
			for (int target : to) {
				used[target] = true;
			}
			int[] state = new int[modules];
			for (int pass = 0; pass < 2; pass++) {
				for (int i = 0; i < modules; i++) {
					if (state[i] == 0 && (pass == 1 || !used[i])) {
						walk(i, state);
					}
				}
			}
		}

		private void walk(int module, int[] state) {
			state[module] = 1;
			for (int e : outgoing.get(module)) {
				if (state[to[e]] == 1) {
					turned[e] = true;
				} else if (state[to[e]] == 0) {
					walk(to[e], state);
				}
			}
			state[module] = 2;
		}

		private int top(int e) {
			return turned[e] ? to[e] : from[e];
		}

		private int bottom(int e) {
			return turned[e] ? from[e] : to[e];
		}

		/** A module's layer is the length of the longest path that leads to it. */
		private int[] assignLayers() {
			List<List<Integer>> incoming = new ArrayList<>();
			for (int i = 0; i < names.size(); i++) {
				incoming.add(new ArrayList<>());
			}
			for (int e = 0; e < edges.size(); e++) {
				incoming.get(bottom(e)).add(e);
			}
			int[] layer = new int[names.size()];
			Arrays.fill(layer, -1);
			for (int i = 0; i < names.size(); i++) {
				layerOf(i, layer, incoming);
			}
			return layer;
		}

		private int layerOf(int module, int[] layer, List<List<Integer>> incoming) {
			if (layer[module] < 0) {
				int longest = 0;
				for (int e : incoming.get(module)) {
					longest = Math.max(longest, layerOf(top(e), layer, incoming) + 1);
				}
				layer[module] = longest;
			}
			return layer[module];
		}

		private void buildSlots(int[] layerOfModule) {
			int modules = names.size();
			int[] portsAbove = new int[modules];
			int[] portsBelow = new int[modules];
			for (int e = 0; e < edges.size(); e++) {
				portsBelow[top(e)]++;
				portsAbove[bottom(e)]++;
			}
			int layerCount = 0;
			for (int i = 0; i < modules; i++) {
				layerCount = Math.max(layerCount, layerOfModule[i] + 1);
				double forName = names.get(i).length() * CHARACTER_WIDTH + 32;
				double forPorts = (Math.max(portsAbove[i], portsBelow[i]) + 1) * PORT_SPACING;
				addSlot(layerOfModule[i], Math.max(MIN_BOX_WIDTH, Math.max(forName, forPorts)));
			}
			for (int l = 0; l < layerCount; l++) {
				layers.add(new ArrayList<>());
			}
			for (int i = 0; i < modules; i++) {
				layers.get(layerOfModule[i]).add(i);
			}
			for (int e = 0; e < edges.size(); e++) {
				List<Integer> chain = new ArrayList<>();
				chain.add(top(e));
				for (int l = layerOfModule[top(e)] + 1; l < layerOfModule[bottom(e)]; l++) {
					int pass = addSlot(l, PASS_WIDTH);
					layers.get(l).add(pass);
					chain.add(pass);
				}
				chain.add(bottom(e));
				for (int i = 0; i + 1 < chain.size(); i++) {
					below.get(chain.get(i)).add(chain.get(i + 1));
					above.get(chain.get(i + 1)).add(chain.get(i));
				}
				chains.add(chain);
			}
		}

		private int addSlot(int layer, double width) {
			slotLayer.add(layer);
			slotWidth.add(width);
			above.add(new ArrayList<>());
			below.add(new ArrayList<>());
			return slotLayer.size() - 1;
		}

		/**
		 * Orders the slots of each layer to limit the crossings. It starts from the order of the names. When
		 * crossings remain it tries again from other orders, the opposite one then a few shuffled ones, and keeps
		 * the first of the best results. The shuffles are seeded, so they are the same on every run.
		 */
		private void orderLayers() {
			List<List<Integer>> fromNames = copyOf(layers);
			int fewest = improveOrder();
			List<List<Integer>> best = layers;
			int starts = slotLayer.size() <= SLOTS_FOR_MORE_STARTS ? MORE_STARTS : 1;
			for (int start = 0; start < starts && fewest > 0; start++) {
				layers = copyOf(fromNames);
				if (start == 0) {
					layers.forEach(Collections::reverse);
				} else {
					Random random = new Random(start);
					layers.forEach(layer -> Collections.shuffle(layer, random));
				}
				int now = improveOrder();
				if (now < fewest) {
					fewest = now;
					best = layers;
				}
			}
			layers = best;
		}

		/**
		 * Orders each layer by the average position of the neighbours in the layer above, then in the layer
		 * below, several times, and keeps the order with the fewest crossings. Then swaps neighbours.
		 *
		 * @return the number of crossings that remain
		 */
		private int improveOrder() {
			List<List<Integer>> best = copyOf(layers);
			int fewest = crossings();
			for (int round = 0; round < 12 && fewest > 0; round++) {
				for (int l = 1; l < layers.size(); l++) {
					orderLayer(l, above);
				}
				for (int l = layers.size() - 2; l >= 0; l--) {
					orderLayer(l, below);
				}
				int now = crossings();
				if (now < fewest) {
					fewest = now;
					best = copyOf(layers);
				}
			}
			layers = best;
			return swapNeighbours(fewest);
		}

		/**
		 * Swaps two slots that are side by side when this removes crossings, until no swap helps. It finds what
		 * the averages miss, such as two modules of the top layer that are better the other way round.
		 *
		 * @return the number of crossings that remain
		 */
		private int swapNeighbours(int crossingsNow) {
			if (slotLayer.size() > SLOTS_FOR_SWAPS) {
				// Too many slots to try every swap in a reasonable time
				return crossingsNow;
			}
			boolean improved = crossingsNow > 0;
			for (int pass = 0; pass < 4 && improved; pass++) {
				improved = false;
				for (int l = 0; l < layers.size(); l++) {
					List<Integer> layer = layers.get(l);
					for (int i = 0; i + 1 < layer.size(); i++) {
						int before = crossingsAround(l);
						Collections.swap(layer, i, i + 1);
						if (before > 0 && crossingsAround(l) < before) {
							improved = true;
						} else {
							Collections.swap(layer, i, i + 1);
						}
					}
				}
			}
			return crossings();
		}

		private int crossingsAround(int l) {
			int[] position = positions();
			return (l > 0 ? crossingsBelow(l - 1, position) : 0) + crossingsBelow(l, position);
		}

		private void orderLayer(int l, List<List<Integer>> neighbours) {
			int[] position = positions();
			List<Integer> layer = layers.get(l);
			Map<Integer, Double> wanted = new HashMap<>();
			for (int slot : layer) {
				List<Integer> others = neighbours.get(slot);
				double sum = 0;
				for (int other : others) {
					sum += position[other];
				}
				wanted.put(slot, others.isEmpty() ? position[slot] : sum / others.size());
			}
			// The sort is stable: slots that want the same place keep their order
			layer.sort(Comparator.comparingDouble(wanted::get));
		}

		private int[] positions() {
			int[] position = new int[slotLayer.size()];
			for (List<Integer> layer : layers) {
				for (int i = 0; i < layer.size(); i++) {
					position[layer.get(i)] = i;
				}
			}
			return position;
		}

		private int crossings() {
			int[] position = positions();
			int count = 0;
			for (int l = 0; l + 1 < layers.size(); l++) {
				count += crossingsBelow(l, position);
			}
			return count;
		}

		/** The crossings between a layer and the next one. */
		private int crossingsBelow(int l, int[] position) {
			List<int[]> segments = new ArrayList<>();
			for (int slot : layers.get(l)) {
				for (int other : below.get(slot)) {
					segments.add(new int[] {position[slot], position[other]});
				}
			}
			int count = 0;
			for (int a = 0; a < segments.size(); a++) {
				for (int b = a + 1; b < segments.size(); b++) {
					if ((segments.get(a)[0] - segments.get(b)[0]) * (segments.get(a)[1] - segments.get(b)[1]) < 0) {
						count++;
					}
				}
			}
			return count;
		}

		private static List<List<Integer>> copyOf(List<List<Integer>> layers) {
			List<List<Integer>> copy = new ArrayList<>();
			for (List<Integer> layer : layers) {
				copy.add(new ArrayList<>(layer));
			}
			return copy;
		}

		/**
		 * Starts with every layer packed and centred, then moves each slot towards its neighbours, layer by
		 * layer, keeping the order and the gaps.
		 */
		private void placeHorizontally() {
			center = new double[slotLayer.size()];
			for (List<Integer> layer : layers) {
				double x = 0;
				for (int slot : layer) {
					center[slot] = x + slotWidth.get(slot) / 2;
					x += slotWidth.get(slot) + COLUMN_GAP;
				}
				double shift = (x - COLUMN_GAP) / 2;
				for (int slot : layer) {
					center[slot] -= shift;
				}
			}
			for (int round = 0; round < 8; round++) {
				for (int l = 1; l < layers.size(); l++) {
					moveTowards(l, above);
				}
				for (int l = layers.size() - 2; l >= 0; l--) {
					moveTowards(l, below);
				}
			}
		}

		/**
		 * Puts the slots of a layer as close as possible to the middle of their neighbours: the positions that
		 * keep the order and the gaps with the smallest sum of squared distances to the wanted ones.
		 */
		private void moveTowards(int l, List<List<Integer>> neighbours) {
			List<Integer> layer = layers.get(l);
			int size = layer.size();
			double[] offset = new double[size];
			double[] wanted = new double[size];
			double[] weight = new double[size];
			for (int i = 0; i < size; i++) {
				int slot = layer.get(i);
				if (i > 0) {
					offset[i] = offset[i - 1] + slotWidth.get(layer.get(i - 1)) / 2 + COLUMN_GAP + slotWidth.get(slot) / 2;
				}
				List<Integer> others = neighbours.get(slot);
				double sum = 0;
				for (int other : others) {
					sum += center[other];
				}
				wanted[i] = (others.isEmpty() ? center[slot] : sum / others.size()) - offset[i];
				// An arrow that passes the layer should stay straight, and a slot without neighbours gives way
				weight[i] = others.isEmpty() ? 0.25 : slot >= names.size() ? 4 : 1;
			}
			// Without the offsets, the positions only have to be in increasing order: pool the ones that are not
			double[] blockSum = new double[size];
			double[] blockWeight = new double[size];
			int[] blockSize = new int[size];
			int blocks = 0;
			for (int i = 0; i < size; i++) {
				blockSum[blocks] = wanted[i] * weight[i];
				blockWeight[blocks] = weight[i];
				blockSize[blocks] = 1;
				blocks++;
				while (blocks > 1 && blockSum[blocks - 2] / blockWeight[blocks - 2]
					> blockSum[blocks - 1] / blockWeight[blocks - 1]) {
					blockSum[blocks - 2] += blockSum[blocks - 1];
					blockWeight[blocks - 2] += blockWeight[blocks - 1];
					blockSize[blocks - 2] += blockSize[blocks - 1];
					blocks--;
				}
			}
			int i = 0;
			for (int block = 0; block < blocks; block++) {
				double value = blockSum[block] / blockWeight[block];
				for (int k = 0; k < blockSize[block]; k++, i++) {
					center[layer.get(i)] = value + offset[i];
				}
			}
		}

		private List<Arrow> routeArrows(List<Box> boxes, double[] layerTop, double[] layerHeight) {
			int modules = names.size();
			// The arrows on each side of a box, from left to right
			List<List<Integer>> portsBelow = new ArrayList<>();
			List<List<Integer>> portsAbove = new ArrayList<>();
			for (int i = 0; i < modules; i++) {
				portsBelow.add(new ArrayList<>());
				portsAbove.add(new ArrayList<>());
			}
			for (int e = 0; e < edges.size(); e++) {
				portsBelow.get(top(e)).add(e);
				portsAbove.get(bottom(e)).add(e);
			}
			Comparator<Integer> upFirst = Comparator.comparing(e -> turned[e]);
			for (int i = 0; i < modules; i++) {
				portsBelow.get(i).sort(Comparator.<Integer>comparingDouble(e -> center[chains.get(e).get(1)])
					.thenComparing(upFirst));
				portsAbove.get(i).sort(Comparator
					.<Integer>comparingDouble(e -> center[chains.get(e).get(chains.get(e).size() - 2)])
					.thenComparing(upFirst));
			}

			List<Arrow> arrows = new ArrayList<>();
			for (int e = 0; e < edges.size(); e++) {
				List<Integer> chain = chains.get(e);
				Box topBox = boxes.get(top(e));
				Box bottomBox = boxes.get(bottom(e));
				double startX = portX(topBox, portsBelow.get(top(e)), e);
				double endX = portX(bottomBox, portsAbove.get(bottom(e)), e);
				double startY = topBox.y() + topBox.height();
				double endY = bottomBox.y();

				// Straight past the boxes of a layer, and a little more: the curves stay between the layers
				int topLayer = topBox.layer();
				int bottomLayer = bottomBox.layer();
				List<Point> points = new ArrayList<>();
				points.add(new Point(startX, startY));
				points.add(new Point(startX, layerTop[topLayer] + layerHeight[topLayer] + STRAIGHT_END));
				for (int slot : chain.subList(1, chain.size() - 1)) {
					int layer = slotLayer.get(slot);
					points.add(new Point(center[slot], layerTop[layer] - STRAIGHT_END));
					points.add(new Point(center[slot], layerTop[layer] + layerHeight[layer] + STRAIGHT_END));
				}
				points.add(new Point(endX, layerTop[bottomLayer] - STRAIGHT_END));
				points.add(new Point(endX, endY));
				if (turned[e]) {
					Collections.reverse(points);
				}
				arrows.add(new Arrow(edges.get(e), List.copyOf(points)));
			}
			return arrows;
		}

		private static double portX(Box box, List<Integer> ports, int e) {
			return round(box.x() + box.width() * (ports.indexOf(e) + 1) / (ports.size() + 1));
		}

		private static double round(double value) {
			return Math.round(value * 10) / 10.0;
		}
	}
}
