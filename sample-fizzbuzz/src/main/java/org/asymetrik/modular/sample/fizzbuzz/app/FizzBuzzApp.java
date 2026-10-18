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
package org.asymetrik.modular.sample.fizzbuzz.app;

import java.util.List;

import org.asymetrik.modular.sample.fizzbuzz.engine.FizzBuzz;
import org.asymetrik.modular.sample.fizzbuzz.rules.Rules;

/**
 * Prints FizzBuzz from 1 to the number given as first argument (100 by default).
 */
public class FizzBuzzApp {

    public static void main(String[] args) {
        int last = args.length > 0 ? Integer.parseInt(args[0]) : 100;
        FizzBuzz fizzBuzz = new FizzBuzz(List.of(Rules.fizz(), Rules.buzz()));
        fizzBuzz.sayUpTo(last).forEach(System.out::println);
    }
}
