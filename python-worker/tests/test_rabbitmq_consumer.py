import json
import sys
import types
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

if "pika" not in sys.modules:
    pika_stub = types.ModuleType("pika")
    pika_stub.PlainCredentials = object
    pika_stub.ConnectionParameters = object
    pika_stub.BlockingConnection = object
    sys.modules["pika"] = pika_stub

from config import RabbitMqSettings
from workers.rabbitmq_consumer import RabbitMqConsumer
from workers.lease_execution import LeaseLost


class RabbitMqConsumerTest(unittest.TestCase):
    def test_missing_lease_protocol_stops_without_processing_or_ack(self):
        callback = Mock()
        callback.claim_task.return_value = {"action": "CLAIMED"}
        processor = Mock()
        consumer = RabbitMqConsumer(RabbitMqSettings(), callback, {"NDVI": processor})
        channel = Mock()
        with self.assertRaises(LeaseLost):
            consumer._handle_message(channel, SimpleNamespace(delivery_tag=10), None,
                                     b'{"taskId":1,"taskType":"NDVI"}')
        processor.process.assert_not_called()
        channel.basic_ack.assert_not_called()
        callback.update_status.assert_not_called()

    def test_lease_loss_is_not_reclassified_as_business_retry(self):
        callback = Mock()
        callback.claim_task.return_value = {
            "action": "CLAIMED", "attemptId": "a",
            "outputObjectKey": "result/a.tif", "leaseSeconds": 120}
        consumer = RabbitMqConsumer(RabbitMqSettings(), callback, {"NDVI": Mock()})
        channel = Mock()
        with patch("workers.rabbitmq_consumer.execute_with_lease", side_effect=LeaseLost("lost")):
            with self.assertRaises(LeaseLost):
                consumer._handle_message(channel, SimpleNamespace(delivery_tag=10), None,
                                         b'{"taskId":1,"taskType":"NDVI"}')
        channel.basic_ack.assert_not_called()
        channel.basic_nack.assert_not_called()
        callback.safe_update_status.assert_not_called()

    def test_success_uses_claim_output_and_attempt(self):
        callback = Mock()
        callback.claim_task.return_value = {
            "action": "CLAIMED", "attemptId": "new-attempt",
            "outputObjectKey": "result/new-attempt.tif", "leaseSeconds": 120}
        processor = Mock()
        processor.process.return_value = {"outputObjectKey": "result/new-attempt.tif"}
        consumer = RabbitMqConsumer(RabbitMqSettings(), callback, {"NDVI": processor})
        channel = Mock()
        consumer._handle_message(channel, SimpleNamespace(delivery_tag=10), None,
                                 json.dumps({"taskId": 1, "taskType": "NDVI", "outputObjectKey": "old.tif"}).encode())
        self.assertEqual(processor.process.call_args.args[0]["outputObjectKey"], "result/new-attempt.tif")
        self.assertEqual(callback.update_status.call_args.kwargs["extra"]["attemptId"], "new-attempt")
        channel.basic_ack.assert_called_once()

    def test_business_failure_carries_attempt(self):
        callback = Mock()
        callback.claim_task.return_value = {
            "action": "CLAIMED", "attemptId": "new-attempt",
            "outputObjectKey": "result/new-attempt.tif", "leaseSeconds": 120}
        processor = Mock()
        processor.process.side_effect = ValueError("no valid pixels")
        consumer = RabbitMqConsumer(RabbitMqSettings(), callback, {"NDVI": processor})
        channel = Mock()
        consumer._handle_message(channel, SimpleNamespace(delivery_tag=10), None,
                                 json.dumps({"taskId": 1, "taskType": "NDVI"}).encode())
        self.assertEqual(callback.update_status.call_args.args[1], "FAILED")
        self.assertEqual(callback.update_status.call_args.kwargs["extra"]["attemptId"], "new-attempt")
        channel.basic_ack.assert_called_once()

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
