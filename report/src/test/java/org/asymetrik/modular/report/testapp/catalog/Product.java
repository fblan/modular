package org.asymetrik.modular.report.testapp.catalog;

import org.asymetrik.modular.report.testapp.catalog.internal.ProductIndex;

public class Product {
	private final String name;

	public Product(String name) {
		this.name = name;
	}

	public String name() {
		return name;
	}

	public boolean isKnown() {
		return new ProductIndex().contains(name);
	}
}
