"""INSPECT/PREFLIGHT/STANDARDIZE share one serial supervisor; LOCAL work never enters MQ."""
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import time
import uuid
from datetime import datetime, timezone

from clients.ingestion_callback_client import IngestionCallbackClient, WorkApiError, canonical_sha
from ingestion.preflight import StageFailure
from ingestion.work_handler import run_work, validate_context
from workers.lease_execution import LeaseLost
from workers.process_supervisor import execute_process_with_lease


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate message field")
        result[key] = value
    return result


def parse_message(body):
    if len(body) > 4096:
        raise ValueError("Message too large")
    message = json.loads(body, object_pairs_hook=_unique_object)
    if (not isinstance(message, dict) or set(message) != {"schemaVersion", "kind", "workId", "targetId", "generation"}
            or type(message["schemaVersion"]) is not int or message["schemaVersion"] != 1
            or message["kind"] not in ("INSPECT", "PREFLIGHT", "STANDARDIZE")
            or type(message["generation"]) is not int or not 1 <= message["generation"] <= 3):
        raise ValueError("Unsupported ingestion message")
    for key in ("workId", "targetId"):
        if type(message[key]) is not str or str(uuid.UUID(message[key])) != message[key]:
            raise ValueError("Invalid work identity")
    return message


class IngestionConsumer:
    def __init__(self, settings, executor_id):
        self.settings, self.executor_id = settings, executor_id
        self.bucket = os.getenv("INGESTION_BUCKET", "")
        if not re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", self.bucket):
            raise RuntimeError("Configure the matching private ingestion bucket before enabling Worker")
        self.callback = IngestionCallbackClient(settings.callback, executor_id)

    def register(self, channel, dispatch=lambda callback: callback):
        channel.basic_consume(queue="rs.ingestion.queue", on_message_callback=dispatch(self.handle))

    def handle(self, channel, method, properties, body):
        try:
            message = parse_message(body)
        except (ValueError, TypeError, KeyError):
            channel.basic_reject(delivery_tag=method.delivery_tag, requeue=False)
            print("[ingestion] rejected invalid message", flush=True)
            return
        work_id = message["workId"]
        claim, directory, started = None, None, False
        stopped = []
        accepted = False
        try:
            request_id = str(uuid.uuid4())
            payload = {"schemaVersion": 1, "workerId": self.executor_id, "claimRequestId": request_id}
            waiting_until = time.monotonic() + 1800
            while True:
                claim = self.callback.post(work_id, "claim", payload, work_digest=canonical_sha(message))
                if claim.get("action") != "BUSY":
                    break
                if time.monotonic() >= waiting_until:
                    raise WorkApiError(409, "HARD_TIMEOUT")
                channel.connection.process_data_events(time_limit=1)
                time.sleep(1)
            if claim.get("action") == "SKIP":
                channel.basic_ack(delivery_tag=method.delivery_tag)
                return
            if claim.get("action") != "EXECUTE":
                raise LeaseLost("Unsupported ingestion claim response")
            context = validate_context(message, claim, self.bucket)
            root = self.settings.worker.temp_dir.resolve()
            used = sum(p.stat().st_size for p in root.rglob("*") if p.is_file() and not p.is_symlink())
            reserve = (context.get("input", {}).get("bytes", 0) + 5 * 1024**3 + 512 * 1024**2 + 1024**3) if message["kind"] in ("INSPECT", "STANDARDIZE") else 1024**2
            if used + reserve > 10 * 1024**3:
                raise StageFailure("RESOURCE_LIMIT")
            directory = Path(tempfile.mkdtemp(prefix=f"ingestion-{claim['attemptId']}-", dir=root))
            sequence = 0

            def heartbeat():
                nonlocal sequence
                sequence += 1
                reply = self.callback.post(work_id, "heartbeat", {"attemptId": claim["attemptId"], "slotFence": claim["slotFence"], "sequence": sequence})
                if not reply.get("continue"):
                    raise LeaseLost("Ingestion lease rejected")

            remaining = (datetime.fromisoformat(claim["hardDeadline"].replace("Z", "+00:00")) - datetime.now(timezone.utc)).total_seconds()
            started = True
            result = execute_process_with_lease(lambda: run_work(message, claim, self.settings, directory, self.bucket), heartbeat,
                lambda: channel.connection.process_data_events(time_limit=.5), deadline_seconds=max(0, remaining), stopped=stopped.append)
            if result.get("ok") is not True:
                raise StageFailure(result.get("reason", "SERVICE_UNAVAILABLE"))
            self.callback.post(work_id, "complete", {"attemptId": claim["attemptId"], "slotFence": claim["slotFence"],
                "resultDigest": canonical_sha(result["result"]), "result": result["result"]})
            accepted = True
        except (StageFailure, WorkApiError, LeaseLost, OSError, RuntimeError) as error:
            reason = error.reason if isinstance(error, (StageFailure, WorkApiError)) else "LEASE_LOST" if isinstance(error, LeaseLost) else "SERVICE_UNAVAILABLE"
            if claim and claim.get("action") == "EXECUTE":
                if reason not in {"INVALID_REQUEST","INVALID_ARCHIVE","UNSUPPORTED_PRODUCT","INVALID_GRID","NO_VALID_PIXELS","REGION_OUTSIDE_PRODUCT","WINDOW_TOO_LARGE","RESOURCE_LIMIT","INPUT_HASH_MISMATCH","HARD_TIMEOUT","LEASE_LOST","SERVICE_UNAVAILABLE"}:
                    reason = "INVALID_REQUEST"
                try:
                    self.callback.post(work_id, "fail", {"attemptId": claim["attemptId"], "slotFence": claim["slotFence"], "reason": reason})
                    accepted = True
                except WorkApiError:
                    pass
            elif isinstance(error, WorkApiError) and error.status in (400, 404, 409):
                channel.basic_reject(delivery_tag=method.delivery_tag, requeue=False)
                return
            print(f"[ingestion] stage ended reason={reason}", flush=True)
        finally:
            if claim and claim.get("action") == "EXECUTE" and (stopped or not started):
                if directory is not None:
                    root = self.settings.worker.temp_dir.resolve()
                    if directory.parent == root and directory.name.startswith("ingestion-") and not directory.is_symlink():
                        shutil.rmtree(directory)
                try:
                    self.callback.post(work_id, "stopped", {"attemptId": claim["attemptId"], "slotFence": claim["slotFence"],
                        "executorId": self.executor_id, "termination": stopped[-1] if stopped else "EXITED"})
                    accepted = True
                except WorkApiError as error:
                    raise LeaseLost("Stopped receipt unavailable; retain recovery fence") from error
        if accepted:
            channel.basic_ack(delivery_tag=method.delivery_tag)
        else:
            raise LeaseLost("Unknown ingestion result; close connection without a retry loop")
