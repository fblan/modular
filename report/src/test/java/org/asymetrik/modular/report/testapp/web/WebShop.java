package org.asymetrik.modular.report.testapp.web;

import javax.xml.parsers.DocumentBuilderFactory;

import org.asymetrik.modular.report.testapp.catalog.Product;
import org.asymetrik.modular.report.testapp.orders.Order;

/** The top of the application: uses orders, catalog, and the JDK module java.xml. */
public class WebShop {
	private final DocumentBuilderFactory pages = DocumentBuilderFactory.newInstance();

	public Order order(String productName) {
		return new Order(new Product(productName));
	}

	public boolean canRender() {
		return pages != null;
	}
}
