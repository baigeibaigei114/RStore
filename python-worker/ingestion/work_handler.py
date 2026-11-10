"""Runs INSPECT/PREFLIGHT in the native child; never accepts external URLs or caller paths."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import uuid

from minio import Minio
import urllib3

from clients.ingestion_callback_client import canonical_sha
from ingestion.inspection import inspect_archive, download_archive
from ingestion.preflight import preflight_window, StageFailure
from ingestion.standardization import standardize_archive


def validate_context(message, claim, bucket):
    if (set(claim) != {"action","attemptId","leaseUntil","hardDeadline","slotFence","context"}
            or type(claim.get("attemptId")) is not str or not re.fullmatch(r"[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}", claim["attemptId"])
            or type(claim.get("slotFence")) is not int or claim["slotFence"] < 1):
        raise StageFailure("INVALID_REQUEST")
    context = claim.get("context", {})
    if (context.get("work") != message or context.get("profileVersion") != "safe-l2a-profile-v1"
            or not isinstance(context.get("canonicalDocument"), str)
            or hashlib.sha256(context["canonicalDocument"].encode()).hexdigest() != context.get("canonicalHash")):
        raise StageFailure("INVALID_REQUEST")
    try:
        document = json.loads(context["canonicalDocument"])
    except (ValueError, TypeError) as error:
        raise StageFailure("INVALID_REQUEST") from error
    if (document.get("profileVersion") != context["profileVersion"]
            or not re.fullmatch(r"[0-9a-f]{64}", document.get("archiveSha256", ""))):
        raise StageFailure("INVALID_REQUEST")
    if message["kind"] == "STANDARDIZE":
        reference = context.get("input", {})
        product_id = reference.get("productId", "")
        try:
            if str(uuid.UUID(product_id)) != product_id:
                raise ValueError("Non-canonical product identity")
        except (ValueError, TypeError, AttributeError) as error:
            raise StageFailure("INVALID_REQUEST") from error
        if (reference.get("bucket") != bucket or reference.get("sha256") != document["archiveSha256"]
                or not re.fullmatch(r"ingestion/raw/" + re.escape(product_id) + r"/[0-9a-f-]{36}/product\.zip", reference.get("key", ""))
                or document.get("sourceGrid") != context.get("descriptor", {}).get("grid")
                or document.get("calibration") != context.get("descriptor", {}).get("calibration")
                or document.get("rawCalibration") != context.get("descriptor", {}).get("rawCalibration")
                or document.get("regionSnapshot") != context.get("regionSnapshot") or document.get("window") != context.get("window")
                or document.get("qualityRules") != {"dnZeroInvalid": True, "sclResampling": "nearest", "sclValidClasses": [4,5,6,7],
                    "validatorVersion": "input-v1", "preprocessingVersion": "s2-reflectance-v1"}
                or canonical_sha(context["regionSnapshot"]) != context.get("regionSnapshotHash")
                or set(context.get("outputs", {})) != {"TIFF", "MANIFEST"}):
            raise StageFailure("INPUT_HASH_MISMATCH")
        for role, suffix, maximum in (("TIFF", "tif", 256 * 1024**2), ("MANIFEST", "json", 65536)):
            output = context["outputs"][role]
            if (output.get("bucket") != bucket or output.get("key") != f"ingestion/artifacts/{message['targetId']}/{claim['attemptId']}/reflectance.{suffix}"
                    or type(output.get("maxBytes")) is not int or not 1 <= output["maxBytes"] <= maximum):
                raise StageFailure("INVALID_REQUEST")
    elif message["kind"] == "INSPECT":
        reference = context.get("input", {})
        target = document.get("productId")
        if (target != message["targetId"] or reference.get("bucket") != bucket
                or reference.get("sha256") != document["archiveSha256"]
                or not re.fullmatch(r"ingestion/raw/" + re.escape(target) + r"/[0-9a-f-]{36}/product\.zip", reference.get("key", ""))):
            raise StageFailure("INVALID_REQUEST")
        output = context.get("outputs", {}).get("PREVIEW", {})
        if (output.get("bucket") != bucket or output.get("key") != f"ingestion/preview/{target}/{claim['attemptId']}/preview.png"
                or type(output.get("maxBytes")) is not int or not 1 <= output["maxBytes"] <= 8 * 1024**2):
            raise StageFailure("INVALID_REQUEST")
    else:
        if (document.get("regionSnapshot") != context.get("regionSnapshot") or document.get("descriptor") != context.get("descriptor")
                or canonical_sha(context["regionSnapshot"]) != context.get("regionSnapshotHash")):
            raise StageFailure("INPUT_HASH_MISMATCH")
    return context


def run_work(message, claim, settings, directory, bucket):
    try:
        context = validate_context(message, claim, bucket)
        if message["kind"] == "PREFLIGHT":
            return {"ok": True, "result": {"window": preflight_window(context["descriptor"]["grid"], context["regionSnapshot"]),
                "sourceGrid": context["descriptor"]["grid"], "regionSnapshotHash": context["regionSnapshotHash"]}}
        budget = context["resourceBudget"]
        maximum = min(int(budget["archiveMaxBytes"]), 2 * 1024**3)
        safety = max(int(budget["diskSafetyBytes"]), 1024**3)
        if shutil.disk_usage(directory).free < context["input"]["bytes"] + safety:
            raise StageFailure("RESOURCE_LIMIT")
        http = urllib3.PoolManager(timeout=urllib3.Timeout(connect=5, read=30), retries=False)
        client = Minio(settings.minio.endpoint, access_key=settings.minio.access_key, secret_key=settings.minio.secret_key,
                       secure=settings.minio.secure, http_client=http)
        archive = Path(directory) / "product.zip"
        download_archive(client, context["input"], archive, maximum)
        if message["kind"] == "STANDARDIZE":
            result = standardize_archive(archive, directory, context)
            for output in result["outputs"]:
                target = context["outputs"][output["role"]]
                suffix = "tif" if output["role"] == "TIFF" else "json"
                client.fput_object(bucket, target["key"], str(Path(directory) / f"reflectance.{suffix}"),
                    content_type="image/tiff" if suffix == "tif" else "application/json", num_parallel_uploads=1)
                if client.stat_object(bucket, target["key"]).size != output["bytes"]:
                    raise StageFailure("INPUT_HASH_MISMATCH")
            return {"ok": True, "result": result}
        result = inspect_archive(archive, directory, max_disk_bytes=min(int(budget["tempDiskMaxBytes"]), 10 * 1024**3), safety_bytes=safety)
        output = context["outputs"]["PREVIEW"]
        if result["preview"]["bytes"] > output["maxBytes"]:
            raise StageFailure("RESOURCE_LIMIT")
        client.fput_object(bucket, output["key"], str(Path(directory) / "preview.png"), content_type="image/png", num_parallel_uploads=1)
        return {"ok": True, "result": result}
    except StageFailure as error:
        return {"ok": False, "reason": error.reason}
    except (ValueError, KeyError, TypeError):
        return {"ok": False, "reason": "INVALID_ARCHIVE" if message["kind"] in ("INSPECT", "STANDARDIZE") else "INVALID_REQUEST"}
    except Exception:
        return {"ok": False, "reason": "SERVICE_UNAVAILABLE"}
