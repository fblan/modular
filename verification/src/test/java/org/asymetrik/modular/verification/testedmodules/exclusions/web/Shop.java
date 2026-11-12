package org.asymetrik.modular.verification.testedmodules.exclusions.web;

import org.asymetrik.modular.verification.testedmodules.exclusions.framework.Link;

/** Hand-written: the Link parameter stands for what a framework weaves in. */
public class Shop {

	private final Link link;

	public Shop(Link link) {
		this.link = link;
	}

	public String name() {
		return "shop " + link;
	}
}
