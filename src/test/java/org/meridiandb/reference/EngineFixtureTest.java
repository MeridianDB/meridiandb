// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.meridiandb.engine.InMemoryEngine;
import org.meridiandb.temporal.CommitPosition;

class EngineFixtureTest {
    @TestFactory
    Stream<DynamicTest> replaysEveryCanonicalHistoryThroughTheEmbeddedEngine() throws IOException {
        var root = ContractFixtures.load();
        var tests = new ArrayList<DynamicTest>();
        for (var history : ContractFixtures.field(root, "histories")) {
            String id = ContractFixtures.text(history, "id");
            tests.add(dynamicTest(id, () -> {
                try (var engine = new InMemoryEngine()) {
                    var target = ContractFixtures.engine(engine);
                    var database = engine.head().databaseId();
                    var views = new HashMap<String, CommitPosition>();
                    for (var step : ContractFixtures.field(history, "steps")) {
                        Object actual;
                        try {
                            actual = ContractFixtures.step(target, database, step, views);
                        } catch (ReferenceModel.Rejected rejected) {
                            actual = ContractFixtures.fields("error", rejected.code());
                        }
                        assertEquals(ContractFixtures.field(step, "expected"), ContractFixtures.json(actual),
                                "engine/" + id + "/" + ContractFixtures.text(step, "id"));
                    }
                }
            }));
        }
        return tests.stream();
    }
}
