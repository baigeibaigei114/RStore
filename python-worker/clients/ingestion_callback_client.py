"""Small authenticated ingestion callbacks; errors never include response text or credentials."""
from decimal import Decimal
import hashlib
import json
import math
import requests


def canonical(value):
    if isinstance(value, dict):
        return "{" + ",".join(json.dumps(k, ensure_ascii=False) + ":" + canonical(value[k]) for k in sorted(value)) + "}"
    if isinstance(value, list):
        return "[" + ",".join(canonical(v) for v in value) + "]"
    if type(value) in (int, float, Decimal):
        if not math.isfinite(value):
            raise ValueError("Non-finite protocol number")
        number = Decimal(str(value))
        if number == 0:
            return "0"
        return format(number.normalize(), "f")
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


def canonical_sha(value):
    return hashlib.sha256(canonical(value).encode("utf-8")).hexdigest()


class WorkApiError(RuntimeError):
    def __init__(self, status, reason):
        super().__init__(reason)
        self.status, self.reason = status, reason


class IngestionCallbackClient:
    def __init__(self, settings, executor_id):
        self.settings, self.executor_id = settings, executor_id
        self.session = requests.Session()
        self.session.trust_env = False

    def post(self, work_id, operation, payload, *, work_digest=None):
        headers = {"X-Worker-Token": self.settings.worker_token, "X-Worker-Protocol": "ingestion-v1", "X-Worker-Id": self.executor_id}
        headers["X-Ingestion-Capability"] = "standardize-v1"
        if work_digest:
            headers["X-Ingestion-Work-Digest"] = work_digest
        url = f"{self.settings.base_url.rstrip('/')}/api/internal/ingestion/work/{work_id}/{operation}"
        try:
            with self.session.post(url, json=payload, headers=headers, timeout=(5, 30), allow_redirects=False, stream=True) as response:
                raw = response.raw.read(1024 * 1024 + 1)
                if len(raw) > 1024 * 1024:
                    raise WorkApiError(503, "SERVICE_UNAVAILABLE")
                result = json.loads(raw)
                if not 200 <= response.status_code < 300 or result.get("code") != 200:
                    reason = (result.get("data") or {}).get("reason", "SERVICE_UNAVAILABLE")
                    if not isinstance(reason, str) or not reason.replace("_", "").isupper() or len(reason) > 64:
                        reason = "SERVICE_UNAVAILABLE"
                    raise WorkApiError(response.status_code, reason)
                return result.get("data")
        except WorkApiError:
            raise
        except (requests.RequestException, ValueError, OSError) as error:
            raise WorkApiError(503, "SERVICE_UNAVAILABLE") from error
