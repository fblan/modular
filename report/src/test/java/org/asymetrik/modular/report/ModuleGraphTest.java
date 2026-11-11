package org.asymetrik.modular.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.asymetrik.modular.report.ModuleGraph.Edge;
import org.asymetrik.modular.verification.ModuleAnalysis;
import org.junit.jupiter.api.Test;

class ModuleGraphTest {

	@Test
	void the_graph_of_an_analysis_has_its_modules_and_their_uses_of_each_other() {
		ModuleGraph graph = ModuleGraph.of(ModuleAnalysis.of("org.asymetrik.modular.report.testapp"));

		assertThat(graph.nodes()).containsExactly("billing", "catalog", "legacy <old> & \"co\"", "orders", "web");
		// The virtual module money and the JPMS module java.xml are not part of it
		assertThat(graph.edges()).containsExactly(
			new Edge("billing", "orders", 1, true),
			new Edge("legacy <old> & \"co\"", "catalog", 1, false),
			new Edge("orders", "billing", 1, true),
			new Edge("orders", "catalog", 1, false),
			new Edge("web", "catalog", 1, false),
			new Edge("web", "orders", 1, false));
		assertThat(graph.cycles()).containsExactly(List.of("billing", "orders"));
	}

	@Test
	void modules_that_depend_on_each_other_through_others_are_one_cycle() {
		ModuleGraph graph = ModuleGraph.of(List.of("a", "b", "c", "d", "e"), Map.of(
			"a", Map.of("b", 2),
			"b", Map.of("c", 1),
			"c", Map.of("a", 3, "d", 1),
			"e", Map.of("a", 1)));

		assertThat(graph.cycles()).containsExactly(List.of("a", "b", "c"));
		assertThat(graph.edgesOf(graph.cycles().get(0))).containsExactly(
			new Edge("a", "b", 2, true), new Edge("b", "c", 1, true), new Edge("c", "a", 3, true));
		assertThat(graph.edges()).filteredOn(edge -> !edge.inCycle())
			.containsExactly(new Edge("c", "d", 1, false), new Edge("e", "a", 1, false));
	}

	@Test
	void separate_cycles_are_told_apart() {
		ModuleGraph graph = ModuleGraph.of(List.of("a", "b", "c", "d"), Map.of(
			"a", Map.of("b", 1),
			"b", Map.of("a", 1, "c", 1),
			"c", Map.of("d", 1),
			"d", Map.of("c", 1)));

		assertThat(graph.cycles()).containsExactly(List.of("a", "b"), List.of("c", "d"));
		// From one cycle to the other is not in a cycle
		assertThat(graph.edges()).contains(new Edge("b", "c", 1, false));
	}

	@Test
	void without_cycle_there_is_none() {
		ModuleGraph graph = ModuleGraph.of(List.of("a", "b", "c"), Map.of("a", Map.of("b", 1, "c", 1), "b",
			Map.of("c", 1)));

		assertThat(graph.cycles()).isEmpty();
		assertThat(graph.edges()).noneMatch(Edge::inCycle);
	}

	@Test
	void a_module_using_itself_and_unknown_names_are_ignored() {
		ModuleGraph graph = ModuleGraph.of(List.of("a", "b"), Map.of("a", Map.of("a", 4, "b", 1, "ghost", 2)));

		assertThat(graph.edges()).containsExactly(new Edge("a", "b", 1, false));
		assertThat(graph.cycles()).isEmpty();
	}
}
