import json
import unittest
from types import SimpleNamespace
from clients.ingestion_callback_client import canonical, canonical_sha
from ingestion.consumer import parse_message
from workers.rabbitmq_consumer import RabbitMqConsumer


class IngestionProtocolTest(unittest.TestCase):
    def test_nested_queue_delivery_waits_for_current_handler_to_finish(self):
        consumer = RabbitMqConsumer(None, SimpleNamespace(runtime_enabled=True), {})
        events = []
        second = consumer._serial_delivery(lambda: events.append("new"))

        def first():
            events.append("old-start")
            second()
            events.append("old-end")

        consumer._serial_delivery(first)()
        self.assertEqual(events, ["old-start", "old-end", "new"])

    def test_java_canonical_fixture_and_decimal_identity(self):
        value = {"z": [-0.0, 1e-4], "a": "中文"}
        self.assertEqual(canonical(value), '{"a":"中文","z":[0,0.0001]}')
        self.assertEqual(canonical_sha(value), canonical_sha({"a": "中文", "z": [0, .0001000]}))
        self.assertNotEqual(canonical_sha({"x": 1.00000001}), canonical_sha({"x": 1.00000002}))

    def test_untrusted_message_fields_and_types_are_rejected(self):
        valid = {"schemaVersion": 1, "kind": "INSPECT", "workId": "11111111-1111-4111-8111-111111111111", "targetId": "22222222-2222-4222-8222-222222222222", "generation": 1}
        self.assertEqual(parse_message(json.dumps(valid).encode()), valid)
        self.assertEqual(parse_message(json.dumps(valid | {"kind": "STANDARDIZE"}).encode())["kind"], "STANDARDIZE")
        for patch in ({"owner": "untrusted"}, {"schemaVersion": True}, {"workId": 1}, {"generation": 0}, {"kind": "VALIDATE"}):
            with self.subTest(patch=patch), self.assertRaises(ValueError):
                parse_message(json.dumps(valid | patch).encode())
