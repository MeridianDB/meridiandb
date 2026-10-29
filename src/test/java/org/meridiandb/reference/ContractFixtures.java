// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.reference;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.meridiandb.engine.EngineException;
import org.meridiandb.engine.InMemoryEngine;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.CommitResult;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.ValuePredicate;
import org.meridiandb.temporal.WriteOperation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Strict fixture notation adapter; neither a public JSON API nor production code. */
final class ContractFixtures {
    static final JsonMapper JSON = JsonMapper.builder().build();

    private ContractFixtures() { }

    static JsonNode load() throws IOException {
        return load("temporal-contract-v0.json");
    }

    static JsonNode load(String name) throws IOException {
        try (var input = ContractFixtures.class.getResourceAsStream("/contracts/" + name)) {
            if (input == null) {
                throw new IllegalStateException("Canonical fixture resource missing: " + name);
            }
            return JSON.readTree(input);
        }
    }

    interface Database {
        CommitResult transact(Transaction request);
        CommitPosition openView(ReadCutoff cutoff);
        TemporalResult query(CommitPosition view, TemporalQuery query);
        Optional<CommitPosition> lookupRequest(RequestId requestId);
    }

    static Database reference(ReferenceModel model) {
        return new Database() {
            public CommitResult transact(Transaction request) { return model.transact(request); }
            public CommitPosition openView(ReadCutoff cutoff) { return model.openView(cutoff); }
            public TemporalResult query(CommitPosition view, TemporalQuery query) { return model.query(view, query); }
            public Optional<CommitPosition> lookupRequest(RequestId id) { return model.lookupRequest(id); }
        };
    }

    static Database engine(InMemoryEngine engine) {
        return new Database() {
            public CommitResult transact(Transaction request) {
                return semantic(() -> engine.transact(request));
            }
            public CommitPosition openView(ReadCutoff cutoff) {
                return semantic(() -> {
                    try (var view = engine.openView(cutoff)) { return view.position(); }
                });
            }
            public TemporalResult query(CommitPosition position, TemporalQuery query) {
                return semantic(() -> {
                    try (var view = engine.openView(new ReadCutoff.At(position))) { return view.query(query); }
                });
            }
            public Optional<CommitPosition> lookupRequest(RequestId id) { return engine.lookupRequest(id); }
        };
    }

    private static <T> T semantic(Supplier<T> action) {
        try {
            return action.get();
        } catch (EngineException rejected) {
            throw new ReferenceModel.Rejected(rejected.code().name());
        }
    }

    static Object step(Database model, DatabaseId database,
                       JsonNode step, Map<String, CommitPosition> views) {
        return switch (text(step, "kind")) {
            case "transact" -> {
                var result = model.transact(transaction(step));
                yield fields("status", "committed", "commit", result.position().sequence(), "replayed", result.replayed());
            }
            case "openView" -> {
                String name = text(step, "viewId");
                if (views.containsKey(name)) {
                    throw new IllegalStateException("Duplicate view alias: " + name);
                }
                var view = model.openView(cutoff(field(step, "cutoff"), database));
                views.put(name, view);
                yield fields("commit", view.sequence());
            }
            case "query" -> {
                boolean named = step.get("viewId") != null;
                if (named == (step.get("cutoff") != null)) {
                    throw new IllegalStateException("Query needs exactly one view selector");
                }
                CommitPosition view;
                if (named) {
                    String name = text(step, "viewId");
                    view = views.get(name);
                    if (view == null) {
                        throw new IllegalStateException("Unknown view alias: " + name);
                    }
                } else {
                    view = model.openView(cutoff(field(step, "cutoff"), database));
                }
                yield result(model.query(view, query(field(step, "query"))));
            }
            case "lookupRequest" -> model.lookupRequest(new RequestId(text(step, "requestId")))
                    .map(p -> fields("status", "committed", "commit", p.sequence()))
                    .orElseGet(() -> fields("status", "not_found"));
            default -> throw new IllegalStateException("Unknown fixture step");
        };
    }

    static Transaction transaction(JsonNode node) {
        return decode("INVALID_REQUEST", () -> {
            var operations = new ArrayList<WriteOperation>();
            var raw = field(node, "operations");
            requireArray(raw);
            for (var op : raw) {
                var entity = new EntityId(text(op, "entity"));
                var interval = interval(op);
                operations.add(switch (text(op, "op")) {
                    case "put" -> new WriteOperation.Put(entity, interval, integer(field(op, "value")));
                    case "retract" -> new WriteOperation.Retract(entity, interval);
                    default -> throw new IllegalArgumentException("Unknown write operation");
                });
            }
            return new Transaction(new RequestId(text(node, "requestId")), operations);
        });
    }

    static TemporalQuery query(JsonNode node) {
        return decode("INVALID_QUERY", () -> switch (text(node, "kind")) {
            case "get" -> new TemporalQuery.Get(new EntityId(text(node, "entity")), integer(field(node, "validAt")));
            case "scan" -> new TemporalQuery.Scan(new EntityId(text(node, "entity")), interval(node));
            case "sum" -> new TemporalQuery.Sum(entities(node), integer(field(node, "validAt")));
            case "select" -> new TemporalQuery.Select(entities(node), integer(field(node, "validAt")),
                    predicate(field(node, "predicate")));
            case "filterScan" -> new TemporalQuery.FilterScan(new EntityId(text(node, "entity")), interval(node),
                    predicate(field(node, "predicate")));
            default -> throw new IllegalArgumentException("Unknown query kind");
        });
    }

    private static List<EntityId> entities(JsonNode node) {
        var entities = new ArrayList<EntityId>();
        var raw = field(node, "entities");
        requireArray(raw);
        for (var entity : raw) { entities.add(new EntityId(string(entity))); }
        return entities;
    }

    private static ValuePredicate predicate(JsonNode node) {
        return new ValuePredicate(ValuePredicate.Operator.valueOf(text(node, "operator")), integer(field(node, "operand")));
    }

    private static ReadCutoff cutoff(JsonNode node, DatabaseId database) {
        return decode("INVALID_CUTOFF", () -> {
            if (node.isString() && "head".equals(node.stringValue())) {
                return ReadCutoff.Head.INSTANCE;
            }
            return new ReadCutoff.At(new CommitPosition(database, integer(node)));
        });
    }

    private static ValidTimeInterval interval(JsonNode node) {
        return new ValidTimeInterval(bound(field(node, "from")), bound(field(node, "to")));
    }

    private static Long bound(JsonNode node) {
        return node.isNull() ? null : integer(node);
    }

    private static long integer(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new IllegalArgumentException("Expected an exact signed 64-bit integer");
        }
        return node.longValue();
    }

    static String text(JsonNode node, String name) {
        return string(field(node, name));
    }

    private static String string(JsonNode node) {
        if (!node.isString()) {
            throw new IllegalArgumentException("Expected a string");
        }
        return node.stringValue();
    }

    static JsonNode field(JsonNode node, String name) {
        if (node == null || !node.isObject() || node.get(name) == null) {
            throw new IllegalArgumentException("Missing field: " + name);
        }
        return node.get(name);
    }

    private static void requireArray(JsonNode node) {
        if (!node.isArray()) {
            throw new IllegalArgumentException("Expected an array");
        }
    }

    private static <T> T decode(String code, Supplier<T> decode) {
        try {
            return decode.get();
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new ReferenceModel.Rejected(code);
        }
    }

    private static Object result(TemporalResult result) {
        return switch (result) {
            case TemporalResult.Point point -> point.value().isPresent()
                    ? fields("present", true, "value", point.value().getAsLong()) : fields("present", false);
            case TemporalResult.Segments segments -> segments.values().stream().map(segment -> fields(
                    "from", segment.interval().fromInclusiveMicros(),
                    "to", segment.interval().toExclusiveMicros(), "value", segment.value())).toList();
            case TemporalResult.Aggregate aggregate -> fields("count", aggregate.count(), "sum", aggregate.sum().toString());
            case TemporalResult.Rows rows -> rows.values().stream().map(row -> fields(
                    "entity", row.entity().value(), "value", row.value())).toList();
        };
    }

    static JsonNode json(Object result) {
        // Reparse to compare JSON numbers structurally, independent of int/long node storage.
        return JSON.readTree(JSON.writeValueAsString(result));
    }

    static Map<String, Object> fields(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }
}
