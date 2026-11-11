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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.asymetrik.modular.verification.ModuleAnalysis;
import org.asymetrik.modular.verification.ModuleAnalysis.Dependency;
import org.asymetrik.modular.verification.ModuleAnalysis.TargetKind;

/**
 * The modules and what they really use of each other, by module name. Virtual and JPMS modules are not part of
 * it.
 */
final class ModuleGraph {

	/**
	 * One module using another one.
	 *
	 * @param from the module that uses
	 * @param to the module that is used
	 * @param uses how many pairs of classes are behind it
	 * @param inCycle whether {@code to} also depends on {@code from}, directly or not
	 */
	record Edge(String from, String to, int uses, boolean inCycle) {}

	private final List<String> nodes;
	private final List<Edge> edges;
	private final List<List<String>> cycles;

	private ModuleGraph(List<String> nodes, List<Edge> edges, List<List<String>> cycles) {
		this.nodes = nodes;
		this.edges = edges;
		this.cycles = cycles;
	}

	static ModuleGraph of(ModuleAnalysis analysis) {
		TreeSet<String> names = new TreeSet<>();
		analysis.modules().forEach(module -> names.add(module.name()));
		Map<String, Map<String, Integer>> uses = new TreeMap<>();
		for (Dependency dependency : analysis.dependencies()) {
			String from = dependency.moduleName();
			String to = dependency.target().name();
			if (dependency.target().kind() == TargetKind.MODULE && names.contains(to)) {
				uses.computeIfAbsent(from, name -> new TreeMap<>()).merge(to, dependency.uses().size(), Integer::sum);
			}
		}
		return of(names, uses);
	}

	/**
	 * @param names the modules
	 * @param uses for a module, the modules it uses and how many uses there are. A module using itself is ignored
	 */
	static ModuleGraph of(Collection<String> names, Map<String, ? extends Map<String, Integer>> uses) {
		List<String> nodes = List.copyOf(new TreeSet<>(names));
		Map<String, List<String>> targets = new TreeMap<>();
		for (String node : nodes) {
			List<String> used = new ArrayList<>();
			Map<String, Integer> usedByNode = uses.get(node);
			for (String target : usedByNode == null ? List.<String>of() : new TreeSet<>(usedByNode.keySet())) {
				if (!target.equals(node) && nodes.contains(target)) {
					used.add(target);
				}
			}
			targets.put(node, used);
		}

		Map<String, Integer> componentOf = new Components(nodes, targets).componentOf;
		Map<Integer, List<String>> members = new TreeMap<>();
		for (String node : nodes) {
			members.computeIfAbsent(componentOf.get(node), component -> new ArrayList<>()).add(node);
		}
		List<List<String>> cycles = new ArrayList<>();
		for (List<String> component : members.values()) {
			if (component.size() > 1) {
				cycles.add(List.copyOf(component));
			}
		}
		cycles.sort(Comparator.comparing(cycle -> cycle.get(0)));

		List<Edge> edges = new ArrayList<>();
		for (String from : nodes) {
			for (String to : targets.get(from)) {
				edges.add(new Edge(from, to, uses.get(from).get(to),
					componentOf.get(from).equals(componentOf.get(to))));
			}
		}
		return new ModuleGraph(nodes, List.copyOf(edges), List.copyOf(cycles));
	}

	/** The names of the modules, sorted. */
	List<String> nodes() {
		return nodes;
	}

	/** Sorted by {@code from}, then by {@code to}. */
	List<Edge> edges() {
		return edges;
	}

	/** The groups of modules that depend on each other. Each group is sorted, and so are the groups. */
	List<List<String>> cycles() {
		return cycles;
	}

	/** The edges between the modules of one cycle. */
	List<Edge> edgesOf(List<String> cycle) {
		return edges.stream().filter(edge -> cycle.contains(edge.from()) && cycle.contains(edge.to())).toList();
	}

	/** Strongly connected components, with Tarjan's algorithm. */
	private static final class Components {
		private final Map<String, List<String>> targets;
		private final Map<String, Integer> order = new HashMap<>();
		private final Map<String, Integer> lowest = new HashMap<>();
		private final Deque<String> stack = new ArrayDeque<>();
		private final Map<String, Integer> componentOf = new HashMap<>();
		private int components;

		Components(List<String> nodes, Map<String, List<String>> targets) {
			this.targets = targets;
			for (String node : nodes) {
				if (!order.containsKey(node)) {
					visit(node);
				}
			}
		}

		private void visit(String node) {
			order.put(node, order.size());
			lowest.put(node, order.get(node));
			stack.push(node);
			for (String target : targets.get(node)) {
				if (!order.containsKey(target)) {
					visit(target);
					lowest.merge(node, lowest.get(target), Math::min);
				} else if (!componentOf.containsKey(target)) {
					// Still on the stack: the target leads back to this node
					lowest.merge(node, order.get(target), Math::min);
				}
			}
			if (lowest.get(node).equals(order.get(node))) {
				String member;
				do {
					member = stack.pop();
					componentOf.put(member, components);
				} while (!member.equals(node));
				components++;
			}
		}
	}
}
