package org.asymetrik.modular.verification.testedmodules.exclusions.web;

import org.asymetrik.modular.verification.testedmodules.exclusions.store.Store;

/** Stands for a class that a framework generates: it uses a module that excl-web does not require. */
public class Shop$$Adapter {

	public Store store() {
		return new Store();
	}

	/** Its nested class: left out with it. */
	public static class Route {
		public Store target() {
			return new Store();
		}
	}
}
