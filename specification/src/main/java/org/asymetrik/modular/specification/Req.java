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
import java.lang.annotation.Repeatable;
import java.lang.annotation.Target;

/**
 * A requirement of a module, written as one sentence in EARS format (Easy Approach to Requirements Syntax).
 * Placed, and repeated, on the {@code package-info.java} of a {@code @Module}. The subject of the sentence is the
 * module, named as in {@code @Module(name = ...)}:
 * <ul>
 *   <li>Ubiquitous: <i>The engine shall ...</i></li>
 *   <li>Event-driven: <i>When &lt;trigger&gt;, the engine shall ...</i></li>
 *   <li>State-driven: <i>While &lt;state&gt;, the engine shall ...</i></li>
 *   <li>Unwanted behaviour: <i>If &lt;condition&gt;, then the engine shall ...</i></li>
 *   <li>Optional feature: <i>Where &lt;feature is included&gt;, the engine shall ...</i></li>
 * </ul>
 * Clauses can be combined: <i>While &lt;state&gt;, when &lt;trigger&gt;, the engine shall ...</i>
 * <p>
 * Checked at compile time by {@code RequirementProcessor}.
 */
@Target(ElementType.PACKAGE)
@Repeatable(Reqs.class)
public @interface Req {
	/** The requirement, as one EARS sentence. */
	String value();

	/** Optional identifier, to reference the requirement from tests or tickets. Unique when set. */
	String id() default "";
}
