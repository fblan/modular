/**
 * Uses java.net.http, which it requires, and java.util, which is in java.base.
 */
@Module(name = "jdkuse", exports = {}, requires = {"java.net.http"})
package org.asymetrik.modular.verification.testedmodules.jdkuse;

import org.asymetrik.modular.annotations.Module;
