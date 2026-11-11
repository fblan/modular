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

import static org.asymetrik.modular.report.Text.escape;
import static org.asymetrik.modular.report.Text.number;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.asymetrik.modular.report.DiagramLayout.Arrow;
import org.asymetrik.modular.report.DiagramLayout.Box;
import org.asymetrik.modular.report.DiagramLayout.Point;

/** Draws the diagram of the modules as an SVG element. The colours come from the page's style sheet. */
final class DiagramSvg {

	private DiagramSvg() {
	}

	/**
	 * @param layout where everything goes
	 * @param label what the diagram shows, for a reader who cannot see it
	 * @param sectionOf for a module name, the id of its section in the page
	 */
	static String render(DiagramLayout layout, String label, Map<String, String> sectionOf) {
		Map<String, Integer> idOf = new HashMap<>();
		for (Box box : layout.boxes()) {
			idOf.put(box.name(), idOf.size() + 1);
		}
		int mostUses = layout.arrows().stream().mapToInt(arrow -> arrow.edge().uses()).max().orElse(1);

		StringBuilder svg = new StringBuilder();
		svg.append("<svg class=\"diagram\" xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
			.append(number(layout.width())).append(' ').append(number(layout.height())).append("\" width=\"")
			.append(number(layout.width())).append("\" height=\"").append(number(layout.height()))
			.append("\" role=\"img\" aria-label=\"").append(escape(label)).append("\">\n");

		svg.append("<g class=\"edges\">\n");
		for (Arrow arrow : layout.arrows()) {
			double stroke = strokeWidth(arrow.edge().uses(), mostUses);
			double headLength = Math.min(13, 7 + stroke * 1.6);
			double headHalfWidth = 3 + stroke * 0.9;
			List<Point> points = arrow.points();
			Point tip = points.get(points.size() - 1);
			// The head is at the end of a vertical line
			double direction = Math.signum(tip.y() - points.get(points.size() - 2).y());
			double headBase = tip.y() - direction * headLength;

			svg.append("<g class=\"edge").append(arrow.edge().inCycle() ? " cycle" : "").append("\" data-from=\"")
				.append(idOf.get(arrow.edge().from())).append("\" data-to=\"").append(idOf.get(arrow.edge().to()))
				.append("\"><title>").append(escape(arrow.edge().from())).append(" uses ")
				.append(escape(arrow.edge().to())).append(": ").append(Text.count(arrow.edge().uses(), "use"))
				.append(arrow.edge().inCycle() ? ", in a cycle" : "").append("</title>");
			svg.append("<path d=\"").append(path(points, headBase)).append("\" stroke-width=\"")
				.append(number(stroke)).append("\"/>");
			svg.append("<polygon points=\"").append(number(tip.x())).append(',').append(number(tip.y())).append(' ')
				.append(number(tip.x() - headHalfWidth)).append(',').append(number(headBase)).append(' ')
				.append(number(tip.x() + headHalfWidth)).append(',').append(number(headBase)).append("\"/></g>\n");
		}
		svg.append("</g>\n");

		svg.append("<g class=\"nodes\">\n");
		for (Box box : layout.boxes()) {
			double middle = box.x() + box.width() / 2;
			double baseline = box.y() + box.height() / 2;
			String size = Text.count(box.types(), "type");
			svg.append("<g class=\"node\" data-module=\"").append(idOf.get(box.name())).append("\" data-name=\"")
				.append(escape(box.name())).append("\" data-section=\"#").append(sectionOf.get(box.name()))
				.append("\" aria-label=\"").append(escape(box.name() + ", " + size)).append("\">");
			svg.append("<rect x=\"").append(number(box.x())).append("\" y=\"").append(number(box.y()))
				.append("\" width=\"").append(number(box.width())).append("\" height=\"")
				.append(number(box.height())).append("\" rx=\"5\"/>");
			svg.append("<text class=\"name\" x=\"").append(number(middle)).append("\" y=\"")
				.append(number(baseline - 2)).append("\" text-anchor=\"middle\">").append(escape(box.name()))
				.append("</text>");
			svg.append("<text class=\"size\" x=\"").append(number(middle)).append("\" y=\"")
				.append(number(baseline + 14)).append("\" text-anchor=\"middle\">").append(size)
				.append("</text></g>\n");
		}
		svg.append("</g>\n</svg>\n");
		return svg.toString();
	}

	/** From thin for one use to thick for the most uses of the diagram. */
	static double strokeWidth(int uses, int mostUses) {
		if (mostUses <= 1) {
			return 1.5;
		}
		return 1.25 + 3.25 * Math.sqrt((uses - 1) / (double) (mostUses - 1));
	}

	/** The line of an arrow, stopped at the base of its head. */
	private static String path(List<Point> route, double headBase) {
		List<Point> points = new ArrayList<>(route);
		Point tip = points.get(points.size() - 1);
		points.set(points.size() - 1, new Point(tip.x(), headBase));

		StringBuilder path = new StringBuilder();
		path.append('M').append(number(points.get(0).x())).append(',').append(number(points.get(0).y()));
		for (int i = 1; i < points.size(); i++) {
			Point before = points.get(i - 1);
			Point point = points.get(i);
			if (before.x() == point.x()) {
				path.append(" L").append(number(point.x())).append(',').append(number(point.y()));
			} else {
				// Leaves and arrives vertically
				String halfway = number((before.y() + point.y()) / 2);
				path.append(" C").append(number(before.x())).append(',').append(halfway).append(' ')
					.append(number(point.x())).append(',').append(halfway).append(' ')
					.append(number(point.x())).append(',').append(number(point.y()));
			}
		}
		return path.toString();
	}
}
