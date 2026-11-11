package org.asymetrik.modular.report.testapp.billing;

import org.asymetrik.modular.report.testapp.money.Amount;
import org.asymetrik.modular.report.testapp.orders.Order;

/** Uses orders, which uses billing: a cycle. */
public class Invoice {
	private final Order order;

	public Invoice(Order order) {
		this.order = order;
	}

	public Order order() {
		return order;
	}

	public Amount total() {
		return new Amount(100);
	}
}
