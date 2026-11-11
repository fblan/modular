package org.asymetrik.modular.report.testapp.catalog.spi;

import java.util.List;

import org.asymetrik.modular.report.testapp.catalog.Product;

/** Exported, but no other module uses it. */
public interface ProductSource {
	List<Product> products();
}
