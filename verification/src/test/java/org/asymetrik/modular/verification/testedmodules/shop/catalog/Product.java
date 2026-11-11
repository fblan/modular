package org.asymetrik.modular.verification.testedmodules.shop.catalog;

import org.asymetrik.modular.verification.testedmodules.shop.catalog.internal.ProductIndex;

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
