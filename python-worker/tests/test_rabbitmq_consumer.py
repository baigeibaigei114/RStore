import json
import sys
import types
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

if "pika" not in sys.modules:
    pika_stub = types.ModuleType("pika")
    pika_stub.PlainCredentials = object
    pika_stub.ConnectionParameters = object
    pika_stub.BlockingConnection = object
    sys.modules["pika"] = pika_stub

from config import RabbitMqSettings
from workers.rabbitmq_consumer import RabbitMqConsumer


class RabbitMqConsumerTest(unittest.TestCase):
    def test_already_running_should_ack_without_requeue(self):
        callback_client = Mock()
        callback_client.claim_task.return_value = {
            "action": "ALREADY_RUNNING",
            "taskStatus": "RUNNING",
        }
        processor = Mock()
        consumer = RabbitMqConsumer(RabbitMqSettings(), callback_client, {"NDVI": processor})
        channel = Mock()
        method = SimpleNamespace(delivery_tag=123)
        body = json.dumps({"taskId": 1, "taskType": "NDVI"}).encode("utf-8")

        consumer._handle_message(channel, method, None, body)

        channel.basic_ack.assert_called_once_with(delivery_tag=123)
        channel.basic_nack.assert_not_called()
        channel.basic_reject.assert_not_called()
        processor.process.assert_not_called()

    def test_already_finished_should_ack_without_processing(self):
        callback_client = Mock()
        callback_client.claim_task.return_value = {
            "action": "ALREADY_FINISHED",
            "taskStatus": "SUCCESS",
        }
        processor = Mock()
        consumer = RabbitMqConsumer(RabbitMqSettings(), callback_client, {"NDVI": processor})
        channel = Mock()
        method = SimpleNamespace(delivery_tag=124)
        body = json.dumps({"taskId": 1, "taskType": "NDVI"}).encode("utf-8")

        consumer._handle_message(channel, method, None, body)

        channel.basic_ack.assert_called_once_with(delivery_tag=124)
        channel.basic_nack.assert_not_called()
        channel.basic_reject.assert_not_called()
        processor.process.assert_not_called()


if __name__ == "__main__":
    unittest.main()
