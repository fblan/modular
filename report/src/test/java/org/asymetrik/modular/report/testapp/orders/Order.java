package org.asymetrik.modular.report.testapp.orders;

import org.asymetrik.modular.report.testapp.billing.Invoice;
import org.asymetrik.modular.report.testapp.catalog.Product;

/** Uses catalog and billing, but nothing of java.compiler, which its module requires. */
public class Order {
	private final Product product;

	public Order(Product product) {
		this.product = product;
	}

	public Product product() {
		return product;
	}

	public Invoice invoice() {
		return new Invoice(this);
	}
}
