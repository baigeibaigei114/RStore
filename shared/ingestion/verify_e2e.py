"""真实服务验收：需 BASELINE_USERNAME/PASSWORD；创建资产和任务，不删除历史数据。"""
import argparse
import copy
import json
import os
import time
from pathlib import Path
from uuid import uuid4

import numpy as np
import rasterio
import requests


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--base-url", default="http://[::1]:8080/api")
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=False)
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    record = {"status": "running", "checks": []}
    session = requests.Session()
    session.trust_env = False
    authenticated = False

    def api(method, path, expected_error=None, **kwargs):
        response = session.request(method, args.base_url + path, timeout=90, **kwargs)
        response.raise_for_status()
        body = response.json()
        if body.get("code") == 429:
            raise RuntimeError("Rate limited: wait for the upload window before rerunning; do not clear Redis")
        if expected_error:
            assert body["code"] != 200 and expected_error in body.get("message", ""), f"Unexpected rejection: {path}"
            return None
        assert body["code"] == 200, f"{path}: {body.get('message')}"
        return body["data"]

    def upload(data=None, expected_error=None):
        with args.input.open("rb") as source:
            files = {"file": (args.input.name, source, "image/tiff")}
            if data is not None:
                files["manifest"] = ("manifest.json", json.dumps(data), "application/json")
            return api("POST", "/images/upload-standardized" if data is not None else "/images/upload",
                       expected_error=expected_error, files=files,
                       data={"name": "ingestion-v1-" + uuid4().hex[:8]})

    try:
        login = api("POST", "/auth/login", json={"username": os.environ["BASELINE_USERNAME"],
                                               "password": os.environ["BASELINE_PASSWORD"]})
        session.headers["Authorization"] = "Bearer " + login["accessToken"]
        authenticated = True
        broken = copy.deepcopy(manifest)
        broken["sha256"] = "0" * 64
        upload(broken, "SHA256")
        broken = copy.deepcopy(manifest)
        broken["schemaVersion"] = 99
        upload(broken, "契约版本")
        record["checks"].append("bad hash and unknown version rejected")

        ordinary = upload()
        record["ordinaryImageId"] = ordinary["id"]
        request = {"imageId": ordinary["id"], "taskType": "NDVI",
                   "params": {"redBand": 3, "nirBand": 4, "inputSnapshot": {"status": "PASSED"}}}
        api("POST", "/tasks", expected_error="标准化", json=request)
        record["checks"].append("ordinary upload and forged snapshot cannot submit")

        image = upload(manifest)
        record["standardizedImageId"] = image["id"]
        admission = json.loads(image["metadataJson"])["admission"]
        assert admission["status"] == "PASSED"
        assert admission["manifest"] == manifest
        request.update(imageId=image["id"], clientRequestId="ingestion-" + uuid4().hex)
        task_id = api("POST", "/tasks", json=request)["taskId"]
        record["taskId"] = task_id
        assert api("POST", "/tasks", json=request)["taskId"] == task_id
        record["checks"].append("standardized upload and idempotent submit")
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            task = api("GET", f"/tasks/{task_id}")
            if task["status"] in ("SUCCESS", "FAILED", "CANCELED"):
                break
            time.sleep(2)
        assert task["status"] == "SUCCESS", "NDVI task failed or timed out"
        snapshot = json.loads(task["params"])["inputSnapshot"]
        assert snapshot["manifest"] == manifest and snapshot["algorithmVersion"] == "ndvi-v1"
        result = api("GET", f"/tasks/{task_id}/result")
        metadata = json.loads(result["resultMetadata"])
        download = api("GET", f"/tasks/{task_id}/result/download-url")
        # 不记录预签名 URL，不向对象存储转发登录 token。
        with requests.Session() as public:
            public.trust_env = False
            response = public.get(download["url"], timeout=60)
            response.raise_for_status()
        with rasterio.open(args.input) as source, rasterio.MemoryFile(response.content) as memory:
            with memory.open() as output:
                red = source.read(3, masked=True).astype("float64").filled(np.nan)
                nir = source.read(4, masked=True).astype("float64").filled(np.nan)
                valid = np.isfinite(red) & np.isfinite(nir) & (np.abs(red + nir) > 1e-6)
                expected = (nir[valid] - red[valid]) / (nir[valid] + red[valid])
                actual = output.read(1, masked=True)
                np.testing.assert_array_equal(~np.ma.getmaskarray(actual), valid)
                np.testing.assert_allclose(actual.data[valid], expected, atol=1e-6)
                assert output.tags().get("input_snapshot_sha256")
                assert source.crs == output.crs and source.transform == output.transform
                stats = metadata["statistics"]
                assert stats["validPixelCount"] == int(valid.sum())
                np.testing.assert_allclose(stats["mean"], expected.mean(), atol=1e-6)
        record["checks"].append("real worker output, snapshot binding and independent NDVI comparison")
        record["statistics"] = metadata["statistics"]
        api("PATCH", f"/images/{image['id']}/band-mapping",
            json={"blueBand": 1, "greenBand": 2, "redBand": 3, "nirBand": 4})
        request["clientRequestId"] = "ingestion-" + uuid4().hex
        api("POST", "/tasks", expected_error="标准化", json=request)
        historical = api("GET", f"/tasks/{task_id}")
        assert json.loads(historical["params"])["inputSnapshot"] == snapshot
        record["checks"].append("band edit invalidates admission without changing historical snapshot")
        record["status"] = "passed"
        print(f"PASS image={image['id']} task={task_id}; modified image now UNVERIFIED")
    except Exception as error:
        record["status"] = "failed"
        record["errorType"] = type(error).__name__
        raise
    finally:
        if authenticated:
            try:
                api("POST", "/auth/logout")
            except (requests.RequestException, AssertionError, RuntimeError):
                pass
        (args.output_dir / "verification.json").write_text(json.dumps(record, indent=2), encoding="utf-8")
        session.close()


if __name__ == "__main__":
    main()
