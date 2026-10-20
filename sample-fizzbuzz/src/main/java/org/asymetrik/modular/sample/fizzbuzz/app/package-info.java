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
/**
 * The application itself. Nothing depends on it, so it exports nothing.
 */
@Module(name = "app", exports = {}, requires = {"engine", "rules"})
@Req(id = "APP-1", value = "Where no argument is given, the app shall print FizzBuzz up to 100.")
@Req(id = "APP-2", value = "If the first argument is not a number, then the app shall stop with an error.")
package org.asymetrik.modular.sample.fizzbuzz.app;

import org.asymetrik.modular.annotations.Module;
import org.asymetrik.modular.specification.Req;
