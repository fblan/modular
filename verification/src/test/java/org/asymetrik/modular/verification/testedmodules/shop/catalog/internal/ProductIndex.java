package org.asymetrik.modular.verification.testedmodules.shop.catalog.internal;

import java.util.ArrayList;
import java.util.List;

public class ProductIndex {
	private final List<Entry> entries = new ArrayList<>();

	public boolean contains(String name) {
		return entries.stream().anyMatch(entry -> entry.matches(name));
	}

	public void add(String name) {
		entries.add(new Entry(name));
	}

	/** Counted with ProductIndex, its top-level type. */
	static class Entry {
		private final String name;

		Entry(String name) {
			this.name = name;
		}

		boolean matches(String other) {
			return name.equals(other);
		}
	}
}
