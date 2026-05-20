import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from clients.callback_client import CallbackClient
from config import CallbackSettings


class CallbackClientTest(unittest.TestCase):
    def test_claim_task_sends_worker_token(self):
        settings = CallbackSettings(base_url="http://backend:8080", worker_token="test-worker-token")
        client = CallbackClient(settings)

        with patch("clients.callback_client.requests.post") as post:
            post.return_value.json.return_value = {"code": 200, "data": {"claimed": True}}
            client.claim_task(1)

        _, kwargs = post.call_args
        self.assertEqual(kwargs["headers"], {"X-Worker-Token": "test-worker-token"})

    def test_update_status_sends_worker_token(self):
        settings = CallbackSettings(base_url="http://backend:8080", worker_token="test-worker-token")
        client = CallbackClient(settings)

        with patch("clients.callback_client.requests.post") as post:
            post.return_value.json.return_value = {"code": 200, "data": None}
            client.update_status(1, "RUNNING")

        _, kwargs = post.call_args
        self.assertEqual(kwargs["headers"], {"X-Worker-Token": "test-worker-token"})


if __name__ == "__main__":
    unittest.main()
