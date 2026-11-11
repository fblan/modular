package org.asymetrik.modular.verification.testedmodules.shop.catalog.spi;

import java.util.List;

import org.asymetrik.modular.verification.testedmodules.shop.catalog.Product;

/** Exported, but no other module uses it. */
public interface ProductSource {
	List<Product> products();
}
