/*
 * Copyright 2026 Asymetryk
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.asymetrik.modular.specification;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Links a test method, or every test of a test class, to the requirements it checks, by their {@link Req#id()}.
 * <p>
 * When the tests compile, {@code RequirementProcessor} reports an error for an id that matches no {@code @Req},
 * and a warning for each requirement with an id that no test covers. It finds the requirements in the index it
 * writes when compiling the main code ({@code META-INF/modular/requirements.properties}).
 * <p>
 * Kept at runtime, so that test reports and extensions can read it.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface TestReq {
	/** Ids of the requirements checked by the test. */
	String[] value();
}
