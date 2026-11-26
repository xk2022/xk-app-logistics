"""Pure tests for the narrowly scoped PostgreSQL CHECK deparser comparison.

No PostgreSQL, Docker, files with private checkpoints, or application runtime.
"""
import copy
import json
import unittest

from m4_integration_db import _canonical_schema, normalize_check_definition


SOURCE = "CHECK (((role)::text = ANY ((ARRAY['ADMIN'::character varying, 'DISPATCHER'::character varying, 'DRIVER'::character varying])::text[])))"
RESTORED = "CHECK (((role)::text = ANY (ARRAY[('ADMIN'::character varying)::text, ('DISPATCHER'::character varying)::text, ('DRIVER'::character varying)::text])))"
CANONICAL = "CHECK (((role)::text = ANY (ARRAY['ADMIN'::text, 'DISPATCHER'::text, 'DRIVER'::text])))"


def schema(definition=SOURCE, flags=None):
    if flags is None:
        flags = [False, False, True, True, False]
    return {"count": 1, "sha256": "0" * 64, "kinds": {"constraint": 1}, "items": [
        {"kind": "constraint", "identity": "upms_account.upms_account_role_check",
         "details": json.dumps(["c", definition, *flags])}]}


class CheckDeparserTest(unittest.TestCase):
    def test_actual_source_and_restored_definitions_are_equivalent(self):
        self.assertEqual(normalize_check_definition(SOURCE), CANONICAL)
        self.assertEqual(normalize_check_definition(RESTORED), CANONICAL)
        self.assertEqual(normalize_check_definition(CANONICAL), CANONICAL)

    def test_literals_and_order_remain_meaningful(self):
        for modified in (
            RESTORED.replace("'DRIVER'", "'SUPERVISOR'"),
            RESTORED.replace("'ADMIN'", "'DRIVER'", 1),
            RESTORED.replace("'ADMIN'::character varying)::text, ", ""),
        ):
            with self.subTest(definition=modified):
                self.assertNotEqual(normalize_check_definition(modified), CANONICAL)
        reverse = RESTORED.replace("'ADMIN'", "'TEMP'", 1).replace("'DRIVER'", "'ADMIN'", 1).replace("'TEMP'", "'DRIVER'", 1)
        self.assertNotEqual(normalize_check_definition(reverse), CANONICAL)

    def test_column_and_operator_are_not_erased(self):
        changed_column = RESTORED.replace("(role)", "(other_role)")
        self.assertIn("(other_role)", normalize_check_definition(changed_column))
        self.assertNotEqual(normalize_check_definition(changed_column), CANONICAL)
        for operator in ("<>", "<", "!="):
            definition = SOURCE.replace(" = ANY", " " + operator + " ANY")
            self.assertEqual(normalize_check_definition(definition), definition)

    def test_bounded_varchar_and_other_types_are_left_raw(self):
        for definition in (
            SOURCE.replace("character varying", "character varying(16)"),
            RESTORED.replace("character varying", "character varying(16)"),
            SOURCE.replace("character varying", "character"),
            SOURCE.replace("::text[]", "::name[]"),
            RESTORED.replace("::text", "::name"),
            SOURCE.replace("::text[]", "::text[]::character varying[]"),
        ):
            with self.subTest(definition=definition):
                self.assertEqual(normalize_check_definition(definition), definition)

    def test_functions_and_mixed_array_expressions_are_left_raw(self):
        for definition in (
            SOURCE.replace("'ADMIN'::character varying", "lower('ADMIN')::character varying"),
            RESTORED.replace("'ADMIN'::character varying", "role::character varying"),
            SOURCE.replace("'ADMIN'::character varying", "('A' || 'DMIN')::character varying"),
            RESTORED.replace("'ADMIN'::character varying", "1::character varying"),
            SOURCE.replace("'ADMIN'::character varying", "NULL::character varying"),
        ):
            with self.subTest(definition=definition):
                self.assertEqual(normalize_check_definition(definition), definition)

    def test_quoted_fake_sql_is_never_interpreted(self):
        for fake in (SOURCE, RESTORED):
            definition = "CHECK (note = '" + fake.replace("'", "''") + "')"
            self.assertEqual(normalize_check_definition(definition), definition)
        quoted_identifier = SOURCE.replace("ARRAY", '"ARRAY"')
        self.assertEqual(normalize_check_definition(quoted_identifier), quoted_identifier)

    def test_escaped_literal_contents_survive_without_token_confusion(self):
        value = "'A,]''::text[] = ANY (ARRAY[''QUOTED'']'"
        original = "CHECK (kind = ANY ((ARRAY[" + value + "::character varying])::text[])))"
        restored = "CHECK (kind = ANY (ARRAY[(" + value + "::character varying)::text])))"
        expected = "CHECK (kind = ANY (ARRAY[" + value + "::text])))"
        self.assertEqual(normalize_check_definition(original), expected)
        self.assertEqual(normalize_check_definition(restored), expected)

    def test_unknown_string_syntax_comments_and_malformed_arrays_stay_raw(self):
        for definition in (
            SOURCE.replace("'ADMIN'", "E'ADMIN'"),
            SOURCE.replace("'ADMIN'", "$tag$ADMIN$tag$"),
            SOURCE + " /* ARRAY['X'] */",
            SOURCE + " -- note",
            SOURCE.replace("'ADMIN'", "'ADMIN"),
            SOURCE.replace("ARRAY[", "ARRAY[,") ,
            SOURCE.replace(", 'DISPATCHER'", ",, 'DISPATCHER'"),
            "CHECK (role = ANY ((ARRAY[])::text[]))",
        ):
            with self.subTest(definition=definition):
                self.assertEqual(normalize_check_definition(definition), definition)

    def test_surrounding_sql_and_multiple_factors_are_preserved(self):
        source = SOURCE[:-1] + " AND " + SOURCE[7:]
        restored = RESTORED[:-1] + " AND " + RESTORED[7:]
        self.assertEqual(normalize_check_definition(source), normalize_check_definition(restored))
        changed = RESTORED[:-1] + " AND enabled)"
        self.assertTrue(normalize_check_definition(changed).endswith(" AND enabled)"))
        self.assertNotEqual(normalize_check_definition(changed), CANONICAL)

    def test_canonical_catalog_hash_matches_and_discards_private_items(self):
        source, restored = schema(), schema(RESTORED)
        _canonical_schema(source)
        _canonical_schema(restored)
        self.assertEqual(source, restored)
        self.assertNotIn("items", source)
        self.assertEqual(set(source), {"count", "sha256", "kinds"})

    def test_every_constraint_flag_remains_in_fingerprint(self):
        baseline = schema()
        _canonical_schema(baseline)
        flags = [False, False, True, True, False]
        for index in range(len(flags)):
            changed_flags = flags.copy()
            changed_flags[index] = not changed_flags[index]
            changed = schema(RESTORED, changed_flags)
            _canonical_schema(changed)
            self.assertNotEqual(changed["sha256"], baseline["sha256"])

    def test_constraint_type_identity_and_other_catalog_text_stay_exact(self):
        baseline = schema()
        _canonical_schema(baseline)
        different_type = schema()
        details = json.loads(different_type["items"][0]["details"])
        details[0] = "u"
        different_type["items"][0]["details"] = json.dumps(details)
        _canonical_schema(different_type)
        self.assertNotEqual(different_type["sha256"], baseline["sha256"])
        for key, value in (("identity", "upms_account.other_check"),
                           ("kind", "user_trigger")):
            changed = schema()
            changed["items"][0][key] = value
            if key == "kind":
                changed["kinds"] = {value: 1}
            _canonical_schema(changed)
            self.assertNotEqual(changed["sha256"], baseline["sha256"])
        original = {"count": 1, "sha256": "0" * 64, "kinds": {"function": 1}, "items": [
            {"kind": "function", "identity": "fn()", "details": SOURCE}]}
        changed = copy.deepcopy(original)
        changed["items"][0]["details"] = RESTORED
        _canonical_schema(original)
        _canonical_schema(changed)
        self.assertNotEqual(original["sha256"], changed["sha256"])


if __name__ == "__main__":
    unittest.main()
