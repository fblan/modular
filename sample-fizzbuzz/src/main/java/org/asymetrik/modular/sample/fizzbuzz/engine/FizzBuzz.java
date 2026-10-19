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
package org.asymetrik.modular.sample.fizzbuzz.engine;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.asymetrik.modular.sample.fizzbuzz.rules.Rule;

/**
 * Applies a list of {@link Rule}s to numbers, falling back to the number itself when no rule matches.
 */
public class FizzBuzz {

    private final List<Rule> rules;

    public FizzBuzz(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public String say(int number) {
        String words = rules.stream()
            .filter(rule -> rule.matches(number))
            .map(Rule::word)
            .collect(Collectors.joining());
        return words.isEmpty() ? String.valueOf(number) : words;
    }

    public List<String> sayUpTo(int last) {
        return IntStream.rangeClosed(1, last).mapToObj(this::say).toList();
    }
}
