import sys
import unittest
from pathlib import Path
from threading import Event

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from workers.lease_execution import LeaseLost, execute_with_lease


class LeaseExecutionTest(unittest.TestCase):
    def test_renews_and_pumps_while_calculation_waits(self):
        finish = Event()
        calls = []

        def process():
            finish.wait(2)
            return {"result": 1}

        def heartbeat():
            calls.append("heartbeat")
            finish.set()

        def pump():
            calls.append("pump")

        result = execute_with_lease(process, heartbeat, pump, 120, interval=0)
        self.assertEqual(result, {"result": 1})
        self.assertIn("heartbeat", calls)
        self.assertIn("pump", calls)

    def test_lease_failure_does_not_return_late_result(self):
        finish = Event()
        def heartbeat():
            raise RuntimeError("backend rejected lease")
        try:
            with self.assertRaises(LeaseLost):
                execute_with_lease(lambda: finish.wait(2), heartbeat, lambda: None, 120, interval=0)
        finally:
            finish.set()

    def test_processing_error_remains_business_error(self):
        def process():
            raise ValueError("invalid raster")
        with self.assertRaisesRegex(ValueError, "invalid raster"):
            execute_with_lease(process, lambda: None, lambda: None, 120)
