package org.asymetrik.modular.verification.testedmodules.modulenotrequiring;

import org.asymetrik.modular.verification.testedmodules.modulea.exported.ClassExported;

public class UndeclaredConsumerClass {
	public String useExportedClassWithoutRequiring() {
		ClassExported exported = new ClassExported();
		return exported.getMessage();
	}
}
