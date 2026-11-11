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
package org.asymetrik.modular.sample.fizzbuzz;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.asymetrik.modular.report.ModularReport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Writes the module report of the sample to {@code target/modular-report/index.html}, to open in a browser.
 */
class ModuleReportTest {

    @Test
    void writesTheModuleReport() throws IOException {
        ModularReport report = ModularReport.of("org.asymetrik.modular.sample.fizzbuzz");

        Path file = report.writeTo(Path.of("target", "modular-report"));

        String html = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(file).hasFileName("index.html");
        assertThat(html).contains("<h3>app</h3>", "<h3>engine</h3>", "<h3>rules</h3>")
            .contains("<dt>Modules</dt><dd class=\"value\">3</dd>")
            .contains("<dt>Dependencies</dt><dd class=\"value\">3</dd>")
            .contains("<title>app uses engine: 1 use</title>")
            .contains("<title>app uses rules: 1 use</title>")
            .contains("<title>engine uses rules: 1 use</title>")
            .contains("No violation of the module rules, no cycle, no class outside the modules.");
    }
}
