"""Unit checks for the isolated ordering experiment cleanup; no Docker needed."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import call, patch
from urllib.error import HTTPError

spec = importlib.util.spec_from_file_location("ordering_run", Path(__file__).with_name("run.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
NAME = "ordering-012345abcdef"
SLOT = "order_012345abcdef"


def not_found():
    return HTTPError("http://localhost/test", 404, "Not Found", {}, None)


def immediate_wait(label, predicate, timeout=120):
    if not predicate():
        raise TimeoutError(label)
    return True


class CleanupTest(unittest.TestCase):
    def test_deletes_only_matching_connector_then_inactive_slot(self):
        with patch.object(module, "request", side_effect=[{"slot.name": SLOT, "database.dbname": "ordering_test"}, None, not_found()]) as req, \
             patch.object(module, "psql", side_effect=["f", "", "0"]) as sql, \
             patch.object(module, "wait_for", side_effect=immediate_wait):
            module.cleanup_connector_and_slot(NAME, SLOT, True)
        self.assertEqual(req.call_args_list[:2], [call(f"/connectors/{NAME}/config"), call(f"/connectors/{NAME}", "DELETE")])
        self.assertIn(SLOT, sql.call_args_list[1].args[0])
        self.assertIn("NOT active", sql.call_args_list[1].args[0])
        self.assertIn("database=current_database()", sql.call_args_list[1].args[0])

    def test_mismatched_connector_is_not_deleted(self):
        with patch.object(module, "request", return_value={"slot.name": "other", "database.dbname": "ordering_test"}) as req, \
             patch.object(module, "psql") as sql:
            with self.assertRaisesRegex(RuntimeError, "refusing cleanup"):
                module.cleanup_connector_and_slot(NAME, SLOT, True)
        req.assert_called_once()
        sql.assert_not_called()

    def test_active_slot_is_not_dropped(self):
        with patch.object(module, "request", side_effect=[{"slot.name": SLOT, "database.dbname": "ordering_test"}, None, not_found()]), \
             patch.object(module, "psql", return_value="t") as sql, \
             patch.object(module, "wait_for", side_effect=immediate_wait):
            with self.assertRaises(TimeoutError):
                module.cleanup_connector_and_slot(NAME, SLOT, True)
        sql.assert_called_once()
        self.assertNotIn("pg_drop_replication_slot", sql.call_args.args[0])

    def test_lost_registration_response_and_absent_connector_fails_closed(self):
        with patch.object(module, "request", side_effect=not_found()), patch.object(module, "psql") as sql:
            with self.assertRaisesRegex(RuntimeError, "outcome is unknown"):
                module.cleanup_connector_and_slot(NAME, SLOT, False)
        sql.assert_not_called()

    def test_lost_response_but_matching_connector_can_be_removed(self):
        with patch.object(module, "request", side_effect=[{"slot.name": SLOT, "database.dbname": "ordering_test"}, None, not_found()]), \
             patch.object(module, "psql", side_effect=["f", "", "0"]), \
             patch.object(module, "wait_for", side_effect=immediate_wait):
            module.cleanup_connector_and_slot(NAME, SLOT, False)

    def test_empty_connector_config_is_not_treated_as_absent(self):
        with patch.object(module, "request", return_value=None), patch.object(module, "psql") as sql:
            with self.assertRaisesRegex(RuntimeError, "configuration response is invalid"):
                module.cleanup_connector_and_slot(NAME, SLOT, True)
        sql.assert_not_called()

    def test_mismatched_names_are_rejected_before_io(self):
        with patch.object(module, "request") as req:
            with self.assertRaises(ValueError):
                module.cleanup_connector_and_slot("ordering-someoneelse", SLOT, True)
        req.assert_not_called()


if __name__ == "__main__":
    unittest.main()
