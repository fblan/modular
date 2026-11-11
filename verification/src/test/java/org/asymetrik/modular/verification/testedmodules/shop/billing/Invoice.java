package org.asymetrik.modular.verification.testedmodules.shop.billing;

import org.asymetrik.modular.verification.testedmodules.shop.orders.Order;

/** Uses shop-orders, which uses shop-billing: a cycle. */
public class Invoice {
	private final Order order;

	public Invoice(Order order) {
		this.order = order;
	}

	public Order order() {
		return order;
	}
}
