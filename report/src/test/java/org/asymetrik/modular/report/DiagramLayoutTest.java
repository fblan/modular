package org.asymetrik.modular.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.asymetrik.modular.report.DiagramLayout.Arrow;
import org.asymetrik.modular.report.DiagramLayout.Box;
import org.asymetrik.modular.report.DiagramLayout.Point;
import org.junit.jupiter.api.Test;

class DiagramLayoutTest {

	/** web on top of a cycle between orders and billing, all of them on catalog. */
	private static final Map<String, Map<String, Integer>> SHOP = Map.of(
		"web", Map.of("orders", 2, "catalog", 1),
		"orders", Map.of("billing", 1, "catalog", 3),
		"billing", Map.of("orders", 1, "catalog", 1));

	@Test
	void a_module_sits_below_every_module_that_uses_it() {
		DiagramLayout layout = layout(Map.of(
			"app", Map.of("engine", 1, "rules", 1, "util", 1),
			"engine", Map.of("rules", 1),
			"rules", Map.of("util", 1)));

		assertThat(layout.boxes()).extracting(Box::name, Box::layer).containsExactly(
			tuple("app", 0), tuple("engine", 1),
			tuple("rules", 2), tuple("util", 3));
		for (Arrow arrow : layout.arrows()) {
			Box from = box(layout, arrow.edge().from());
			Box to = box(layout, arrow.edge().to());
			assertThat(from.y() + from.height()).isLessThan(to.y());
		}
	}

	@Test
	void every_arrow_outside_a_cycle_points_down() {
		DiagramLayout layout = layout(SHOP);

		assertThat(layout.arrows()).filteredOn(arrow -> !arrow.edge().inCycle()).hasSize(4)
			.allMatch(Arrow::pointsDown);
		// A cycle cannot have all its arrows pointing down: one of the two is turned
		assertThat(layout.arrows()).filteredOn(arrow -> arrow.edge().inCycle()).hasSize(2)
			.filteredOn(arrow -> !arrow.pointsDown()).hasSize(1);
	}

	@Test
	void the_modules_that_nobody_uses_are_at_the_top_even_next_to_a_cycle() {
		DiagramLayout layout = layout(SHOP);

		assertThat(box(layout, "web").layer()).isZero();
		assertThat(box(layout, "orders").layer()).isEqualTo(1);
		assertThat(box(layout, "catalog").layer()).isGreaterThan(box(layout, "billing").layer());
	}

	@Test
	void boxes_do_not_overlap_and_stay_inside_the_diagram() {
		DiagramLayout layout = layout(bigGraph());

		List<Box> boxes = layout.boxes();
		assertThat(boxes).hasSize(14);
		for (Box box : boxes) {
			assertThat(box.x()).isGreaterThanOrEqualTo(DiagramLayout.MARGIN - 0.2);
			assertThat(box.y()).isGreaterThanOrEqualTo(DiagramLayout.MARGIN - 0.2);
			assertThat(box.x() + box.width()).isLessThanOrEqualTo(layout.width() - DiagramLayout.MARGIN + 0.2);
			assertThat(box.y() + box.height()).isLessThanOrEqualTo(layout.height() - DiagramLayout.MARGIN + 0.2);
		}
		for (int i = 0; i < boxes.size(); i++) {
			for (int j = i + 1; j < boxes.size(); j++) {
				Box a = boxes.get(i);
				Box b = boxes.get(j);
				boolean apart = a.x() + a.width() <= b.x() || b.x() + b.width() <= a.x()
					|| a.y() + a.height() <= b.y() || b.y() + b.height() <= a.y();
				assertThat(apart).as("%s and %s", a, b).isTrue();
			}
		}
	}

	@Test
	void arrows_never_go_through_a_box() {
		for (Map<String, Map<String, Integer>> uses : List.of(SHOP, bigGraph())) {
			DiagramLayout layout = layout(uses);
			for (Arrow arrow : layout.arrows()) {
				for (int i = 0; i + 1 < arrow.points().size(); i++) {
					Point p = arrow.points().get(i);
					Point q = arrow.points().get(i + 1);
					double top = Math.min(p.y(), q.y());
					double bottom = Math.max(p.y(), q.y());
					for (Box box : layout.boxes()) {
						boolean besideOrBeyond = bottom <= box.y() || top >= box.y() + box.height();
						if (p.x() == q.x()) {
							// A vertical line: beside the box, or above or below it
							boolean beside = p.x() < box.x() || p.x() > box.x() + box.width();
							assertThat(beside || besideOrBeyond).as("%s through %s", arrow, box).isTrue();
						} else {
							// A curve: only between two layers
							assertThat(besideOrBeyond).as("%s through %s", arrow, box).isTrue();
						}
					}
				}
			}
		}
	}

	@Test
	void an_arrow_starts_and_ends_on_the_sides_of_its_boxes() {
		DiagramLayout layout = layout(SHOP);

		for (Arrow arrow : layout.arrows()) {
			Box from = box(layout, arrow.edge().from());
			Box to = box(layout, arrow.edge().to());
			Point start = arrow.points().get(0);
			Point end = arrow.points().get(arrow.points().size() - 1);
			assertThat(start.x()).isBetween(from.x(), from.x() + from.width());
			assertThat(end.x()).isBetween(to.x(), to.x() + to.width());
			if (arrow.pointsDown()) {
				assertThat(start.y()).isEqualTo(from.y() + from.height());
				assertThat(end.y()).isEqualTo(to.y());
			} else {
				assertThat(start.y()).isEqualTo(from.y());
				assertThat(end.y()).isEqualTo(to.y() + to.height());
			}
			// The head of the arrow is at the end of a straight vertical line
			Point beforeEnd = arrow.points().get(arrow.points().size() - 2);
			assertThat(beforeEnd.x()).isEqualTo(end.x());
			assertThat(Math.abs(end.y() - beforeEnd.y())).isGreaterThanOrEqualTo(DiagramLayout.STRAIGHT_END);
		}
	}

	@Test
	void two_modules_that_use_each_other_have_two_arrows_side_by_side() {
		DiagramLayout layout = layout(Map.of("a", Map.of("b", 1), "b", Map.of("a", 1)));

		Arrow down = layout.arrows().get(0);
		Arrow up = layout.arrows().get(1);
		assertThat(down.pointsDown()).isTrue();
		assertThat(up.pointsDown()).isFalse();
		assertThat(down.points().get(0).x()).isLessThan(up.points().get(up.points().size() - 1).x());
		assertThat(down.points().get(down.points().size() - 1).x()).isLessThan(up.points().get(0).x());
	}

	@Test
	void the_order_in_a_layer_avoids_crossings() {
		// In the order of the names, a-d and b-c would cross
		DiagramLayout layout = layout(Map.of("a", Map.of("d", 1), "b", Map.of("c", 1)));

		boolean aLeftOfB = box(layout, "a").x() < box(layout, "b").x();
		boolean dLeftOfC = box(layout, "d").x() < box(layout, "c").x();
		assertThat(aLeftOfB).isEqualTo(dLeftOfC);
	}

	@Test
	void other_starting_orders_are_tried_when_crossings_remain() {
		// From the order of the names, the arrow from legacy to catalog crosses the one from web to orders
		DiagramLayout layout = layout(Map.of(
			"legacy", Map.of("catalog", 1),
			"web", Map.of("orders", 1, "catalog", 1),
			"orders", Map.of("billing", 1, "catalog", 1),
			"billing", Map.of("orders", 1)));

		// No crossing is left: web, its arrows and what it uses are all on the same side of legacy and its arrow
		boolean webOnTheLeft = box(layout, "web").x() < box(layout, "legacy").x();
		assertThat(box(layout, "billing").x() < box(layout, "catalog").x()).isEqualTo(webOnTheLeft);
		assertThat(startX(layout, "web", "orders") < startX(layout, "web", "catalog")).isEqualTo(webOnTheLeft);
		assertThat(endX(layout, "orders", "catalog") < endX(layout, "web", "catalog")).isEqualTo(webOnTheLeft);
		assertThat(endX(layout, "web", "catalog") < endX(layout, "legacy", "catalog")).isEqualTo(webOnTheLeft);
	}

	@Test
	void a_module_with_more_types_has_a_taller_box() {
		SortedMap<String, Integer> types = new TreeMap<>(Map.of("big", 40, "small", 2, "empty", 0));
		DiagramLayout layout = DiagramLayout.of(types, List.of());

		assertThat(box(layout, "big").height()).isGreaterThan(box(layout, "small").height());
		assertThat(box(layout, "small").height()).isGreaterThan(box(layout, "empty").height());
		assertThat(box(layout, "big").types()).isEqualTo(40);
	}

	@Test
	void a_long_name_has_a_wider_box() {
		DiagramLayout layout = layout(Map.of("a-module-with-a-very-long-name-indeed", Map.of("b", 1)));

		assertThat(box(layout, "a-module-with-a-very-long-name-indeed").width()).isGreaterThan(
			box(layout, "b").width());
	}

	@Test
	void the_layout_is_the_same_on_every_run() {
		DiagramLayout first = layout(bigGraph());
		DiagramLayout second = layout(bigGraph());

		assertThat(second.boxes()).isEqualTo(first.boxes());
		assertThat(second.arrows()).isEqualTo(first.arrows());
		assertThat(second.width()).isEqualTo(first.width());
		assertThat(second.height()).isEqualTo(first.height());
	}

	@Test
	void without_module_there_is_nothing_to_draw() {
		DiagramLayout layout = DiagramLayout.of(new TreeMap<>(), List.of());

		assertThat(layout.boxes()).isEmpty();
		assertThat(layout.arrows()).isEmpty();
		assertThat(layout.width()).isZero();
	}

	/** Fourteen modules, with arrows that pass several layers and two cycles. */
	private static Map<String, Map<String, Integer>> bigGraph() {
		Map<String, Map<String, Integer>> uses = new TreeMap<>();
		for (int i = 0; i < 14; i++) {
			Map<String, Integer> used = new TreeMap<>();
			for (int j = i + 1; j < 14; j++) {
				if ((i * 7 + j * 3) % 5 == 0 || j == i + 4) {
					used.put("m" + (char) ('a' + j), 1 + (i + j) % 4);
				}
			}
			uses.put("m" + (char) ('a' + i), used);
		}
		uses.get("mh").put("mc", 2);
		uses.get("mn").put("mk", 1);
		return uses;
	}

	private static DiagramLayout layout(Map<String, Map<String, Integer>> uses) {
		SortedMap<String, Integer> types = new TreeMap<>();
		uses.forEach((from, used) -> {
			types.putIfAbsent(from, from.length());
			used.keySet().forEach(to -> types.putIfAbsent(to, to.length() * 2));
		});
		return DiagramLayout.of(types, new ArrayList<>(ModuleGraph.of(types.keySet(), uses).edges()));
	}

	private static double startX(DiagramLayout layout, String from, String to) {
		return arrow(layout, from, to).points().get(0).x();
	}

	private static double endX(DiagramLayout layout, String from, String to) {
		List<Point> points = arrow(layout, from, to).points();
		return points.get(points.size() - 1).x();
	}

	private static Arrow arrow(DiagramLayout layout, String from, String to) {
		return layout.arrows().stream()
			.filter(arrow -> arrow.edge().from().equals(from) && arrow.edge().to().equals(to)).findFirst().orElseThrow();
	}

	private static Box box(DiagramLayout layout, String name) {
		return layout.boxes().stream().filter(box -> box.name().equals(name)).findFirst().orElseThrow();
	}
}
