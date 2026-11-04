"""Bounded native execution checks; real Linux processes, no remote services."""
import os
from pathlib import Path
import tempfile
import time
import unittest

from workers.process_supervisor import execute_process_with_lease
from workers.lease_execution import LeaseLost


@unittest.skipUnless(os.name == "posix", "Supported Worker is Linux")
class ProcessSupervisorTest(unittest.TestCase):
    def test_result_only_after_native_child_stopped(self):
        receipts = []
        result = execute_process_with_lease(lambda: {"pid": os.getpid()}, lambda: None,
            lambda: time.sleep(.01), deadline_seconds=2, stopped=receipts.append)
        with self.assertRaises(ProcessLookupError):
            os.kill(result["pid"], 0)
        self.assertEqual(receipts, ["EXITED"])

    def test_lease_loss_and_hard_deadline_stop_the_real_process(self):
        for lose_lease in (False, True):
            with self.subTest(lose_lease=lose_lease), tempfile.TemporaryDirectory() as temp:
                path = Path(temp) / "pid"
                receipts = []

                def work():
                    path.write_text(str(os.getpid()))
                    time.sleep(30)

                def heartbeat():
                    if lose_lease:
                        raise ConnectionError("fixture disconnected")

                with self.assertRaises(LeaseLost):
                    execute_process_with_lease(work, heartbeat, lambda: time.sleep(.01),
                        deadline_seconds=1 if lose_lease else .2, heartbeat_seconds=.05, stopped=receipts.append)
                with self.assertRaises(ProcessLookupError):
                    os.kill(int(path.read_text()), 0)
                self.assertEqual(receipts, ["KILLED"])


if __name__ == "__main__":
    unittest.main()
