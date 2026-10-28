// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.DatabaseId;

class ReferenceFixtureTest {
    @TestFactory
    Stream<DynamicTest> replaysEveryCanonicalHistory() throws IOException {
        var root = ContractFixtures.load();
        assertEquals("meridiandb-temporal-contract-v0", ContractFixtures.text(root, "format"));
        var histories = ContractFixtures.field(root, "histories");
        assertEquals(5, histories.size());
        int expectationCount = 0;
        var historyIds = new HashSet<String>();
        var tests = new ArrayList<DynamicTest>();
        for (var history : histories) {
            String id = ContractFixtures.text(history, "id");
            assertTrue(historyIds.add(id), "Duplicate history identity");
            var steps = ContractFixtures.field(history, "steps");
            expectationCount += steps.size();
            var stepIds = new HashSet<String>();
            for (var step : steps) {
                assertTrue(stepIds.add(ContractFixtures.text(step, "id")), "Duplicate step identity");
            }
            tests.add(dynamicTest(id, () -> {
                var database = new DatabaseId(id);
                var model = new ReferenceModel(database);
                var views = new HashMap<String, CommitPosition>();
                for (var step : steps) {
                    Object actual;
                    try {
                        actual = ContractFixtures.step(ContractFixtures.reference(model), database, step, views);
                    } catch (ReferenceModel.Rejected rejected) {
                        actual = ContractFixtures.fields("error", rejected.code());
                    }
                    assertEquals(ContractFixtures.field(step, "expected"), ContractFixtures.json(actual),
                            id + "/" + ContractFixtures.text(step, "id"));
                }
            }));
        }
        assertEquals(84, expectationCount, "Replay every existing expected result");
        return tests.stream();
    }
}
