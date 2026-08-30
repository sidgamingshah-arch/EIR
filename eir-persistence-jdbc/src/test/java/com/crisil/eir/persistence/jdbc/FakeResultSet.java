package com.crisil.eir.persistence.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A one-row {@link ResultSet} over a map, for testing {@link Rows} without a database.
 *
 * <p>A dynamic proxy rather than a hand-written implementation because {@code ResultSet} declares
 * around two hundred methods and a class implementing all of them would bury the six that matter.
 * Anything not stubbed throws, which is the useful behaviour: a reader added to {@link Rows} that
 * reaches for a method this fake does not serve — {@code getDouble} above all — fails here rather
 * than passing quietly.
 */
final class FakeResultSet {

    private FakeResultSet() {
    }

    /** A result set positioned on one row whose columns are {@code values}. */
    static ResultSet of(Map<String, Object> values) {
        Map<String, Object> row = new LinkedHashMap<>(values);
        // wasNull() is stateful in JDBC: it reports on the LAST column read, which is the whole
        // reason Rows.integerOrNull can distinguish a stored 0 from a SQL NULL. Modelled here, so
        // the fake does not accidentally make that distinction easier than it is.
        boolean[] lastWasNull = {false};

        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "next" -> Boolean.TRUE;
            case "wasNull" -> lastWasNull[0];
            case "getBigDecimal" -> track(row, args, lastWasNull);
            case "getString" -> {
                Object value = track(row, args, lastWasNull);
                yield value == null ? null : value.toString();
            }
            case "getTimestamp", "getArray" -> track(row, args, lastWasNull);
            case "getInt" -> {
                Object value = track(row, args, lastWasNull);
                yield value == null ? Integer.valueOf(0) : value;
            }
            case "getBoolean" -> {
                Object value = track(row, args, lastWasNull);
                yield value == null ? Boolean.FALSE : value;
            }
            case "getObject" -> track(row, args, lastWasNull);
            case "toString" -> "FakeResultSet" + row;
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> throw new UnsupportedOperationException(
                "FakeResultSet does not serve " + method.getName() + "; if Rows now needs it, add"
                    + " it deliberately — and note that getDouble is not served on purpose, because"
                    + " a money or rate column read through it silently loses digits the schema"
                    + " exists to keep");
        };

        return (ResultSet) Proxy.newProxyInstance(
            FakeResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class}, handler);
    }

    private static Object track(
        Map<String, Object> row, Object[] args, boolean[] lastWasNull) {

        String column = (String) args[0];
        if (!row.containsKey(column)) {
            throw new IllegalStateException(
                "no column '" + column + "' in this fake row: " + row.keySet());
        }
        Object value = row.get(column);
        lastWasNull[0] = value == null;
        return value;
    }
}
