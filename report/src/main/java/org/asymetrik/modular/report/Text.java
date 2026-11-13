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

import java.util.List;
import java.util.Locale;

/** The small pieces of text the report needs: escaping, plurals, numbers. */
final class Text {

	private Text() {
	}

	/** Escapes a text for HTML and SVG, in an element or in a quoted attribute. */
	static String escape(String text) {
		StringBuilder escaped = new StringBuilder(text.length() + 16);
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '&' -> escaped.append("&amp;");
				case '<' -> escaped.append("&lt;");
				case '>' -> escaped.append("&gt;");
				case '"' -> escaped.append("&quot;");
				case '\'' -> escaped.append("&#39;");
				default -> escaped.append(c);
			}
		}
		return escaped.toString();
	}

	/** "1 use", "3 uses", "2 classes". */
	static String count(long number, String singular) {
		if (number == 1) {
			return "1 " + singular;
		}
		return number + " " + (singular.endsWith("s") ? singular + "es" : singular + "s");
	}

	/** "'a' and 'b'", "'a', 'b' and 'c'". */
	static String quotedList(List<String> names) {
		StringBuilder list = new StringBuilder();
		for (int i = 0; i < names.size(); i++) {
			if (i > 0) {
				list.append(i == names.size() - 1 ? " and " : ", ");
			}
			list.append('\'').append(names.get(i)).append('\'');
		}
		return list.toString();
	}

	/** A coordinate or a size, with one decimal at most. */
	static String number(double value) {
		String text = String.format(Locale.ROOT, "%.1f", value);
		if (text.endsWith(".0")) {
			text = text.substring(0, text.length() - 2);
		}
		return text.equals("-0") ? "0" : text;
	}
}
