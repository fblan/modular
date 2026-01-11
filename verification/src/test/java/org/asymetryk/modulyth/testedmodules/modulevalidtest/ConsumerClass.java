package org.asymetryk.modulyth.testedmodules.modulevalidtest;

import org.asymetryk.modulyth.testedmodules.modulea.exported.ClassExported;

public class ConsumerClass {
	public String useExportedClass() {
		ClassExported exported = new ClassExported();
		return exported.getMessage();
	}
}