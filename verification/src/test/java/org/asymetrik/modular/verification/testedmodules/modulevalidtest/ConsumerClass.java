package org.asymetrik.modular.verification.testedmodules.modulevalidtest;

import org.asymetrik.modular.verification.testedmodules.modulea.exported.ClassExported;

public class ConsumerClass {
	public String useExportedClass() {
		ClassExported exported = new ClassExported();
		return exported.getMessage();
	}
}