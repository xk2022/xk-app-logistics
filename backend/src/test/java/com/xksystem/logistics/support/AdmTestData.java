package com.xksystem.logistics.support;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.Map;

/** Deliberately synthetic, test-classpath-only parameter governance metadata. */
public final class AdmTestData {
    private AdmTestData() {
    }

    public static Map<String, String> insertDefinitions(Connection connection, String suffix) throws Exception {
        String text = "SYNTHETIC_TEXT_" + suffix;
        String number = "SYNTHETIC_NUMBER_" + suffix;
        String bool = "SYNTHETIC_BOOLEAN_" + suffix;
        insert(connection, text, "TEXT", 2, 12, "[]", null, null, null);
        insert(connection, number, "NUMBER", null, null, "[]", BigDecimal.ZERO, new BigDecimal("100"), 2);
        insert(connection, bool, "BOOLEAN", null, null, "[]", null, null, null);
        return Map.of("text", text, "number", number, "boolean", bool);
    }

    public static void insertTextEnumeration(Connection connection, String key) throws Exception {
        insert(connection, key, "TEXT", null, null, "[\"ALPHA\",\"BETA\"]", null, null, null);
    }

    private static void insert(Connection connection, String key, String type, Integer minimumLength,
            Integer maximumLength, String allowedValues, BigDecimal minimum, BigDecimal maximum, Integer scale) throws Exception {
        try (var statement = connection.prepareStatement("""
                INSERT INTO logistics.adm_parameter_definition
                    (parameter_key,name,value_type,description,min_length,max_length,allowed_values,min_value,max_value,decimal_scale)
                VALUES (?,?,?,?,?,?,?::jsonb,?,?,?)
                """)) {
            statement.setString(1, key);
            statement.setString(2, "合成測試參數 " + type);
            statement.setString(3, type);
            statement.setString(4, "Isolated test fixture only; not an approved production business parameter");
            statement.setObject(5, minimumLength);
            statement.setObject(6, maximumLength);
            statement.setString(7, allowedValues);
            statement.setBigDecimal(8, minimum);
            statement.setBigDecimal(9, maximum);
            statement.setObject(10, scale);
            statement.executeUpdate();
        }
    }
}
