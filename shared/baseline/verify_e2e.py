"""真实服务基线：登录、上传、幂等提交、轮询、下载、数值比对和 WMS。

凭据仅从 BASELINE_USERNAME / BASELINE_PASSWORD 环境变量读取，不写入记录。
会创建影像和任务，默认保留以便页面验收。不会调用任何 AI 接口。
"""

import argparse
import io
import json
import os
import time
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

import numpy as np
import requests
import rasterio
from PIL import Image
from rasterio.warp import transform_bounds


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--base-url", default="http://localhost:8080/api")
    parser.add_argument("--wms-url", default="http://localhost:8081/geoserver/wms")
    parser.add_argument("--expect-no-valid-pixels", action="store_true")
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=False)
    session = requests.Session()
    session.trust_env = False
    public_session = requests.Session()
    public_session.trust_env = False

    def api(method, path, **kwargs):
        record["lastApi"] = method + " " + path
        response = session.request(method, args.base_url + path, timeout=60, **kwargs)
        if response.status_code != 200:
            raise RuntimeError(f"{method} {path}: HTTP {response.status_code}")
        body = response.json()
        if body.get("code") != 200:
            raise RuntimeError(f"{method} {path}: business code {body.get('code')}: {body.get('message')}")
        return body["data"]

    record = {"startedAt": datetime.now(timezone.utc).isoformat(), "input": args.input.name}
    signed_in = False
    try:
        login = api("POST", "/auth/login", json={
            "username": os.environ["BASELINE_USERNAME"],
            "password": os.environ["BASELINE_PASSWORD"],
        })
        session.headers["Authorization"] = "Bearer " + login["accessToken"]
        signed_in = True
        manifest = json.loads(args.input.with_suffix(".json").read_text(encoding="utf-8"))
        fields = {"name": "NDVI baseline " + args.input.stem, "sensor": "Sentinel-2"}
        if manifest.get("captureTime"):
            fields["captureTime"] = manifest["captureTime"]
        with args.input.open("rb") as source:
            image = api("POST", "/images/upload",
                        files={"file": (args.input.name, source, "image/tiff")}, data=fields)
        record["imageId"] = image["id"]
        print(f"Uploaded imageId={image['id']}", flush=True)
        request = {"imageId": image["id"], "taskType": "NDVI",
                   "clientRequestId": "baseline-" + uuid4().hex,
                   "params": {"redBand": 3, "nirBand": 4}}
        task_id = api("POST", "/tasks", json=request)["taskId"]
        record["taskId"] = task_id
        assert api("POST", "/tasks", json=request)["taskId"] == task_id, "Duplicate submission"
        record["idempotentSubmit"] = True
        print(f"Submitted taskId={task_id}", flush=True)
        deadline = time.monotonic() + 180
        result = None
        while time.monotonic() < deadline:
            task = api("GET", f"/tasks/{task_id}")
            if task["status"] in ("FAILED", "CANCELED"):
                if args.expect_no_valid_pixels and task["status"] == "FAILED":
                    # 错误信息可能由 DLQ 汇总覆盖，另查日志确认实际无效像素原因。
                    logs = api("GET", f"/tasks/{task_id}/logs")
                    assert "no valid pixels" in json.dumps(logs), "Unexpected failure reason"
                    record["taskStatus"] = "FAILED"
                    record["expectedFailure"] = "no valid pixels"
                    record["status"] = "passed"
                    print(f"PASS expected failure imageId={image['id']} taskId={task_id}")
                    return
                raise RuntimeError(f"Task ended with {task['status']}: {task.get('errorMessage')}")
            if task["status"] == "SUCCESS":
                assert not args.expect_no_valid_pixels, "Invalid input unexpectedly succeeded"
                result = api("GET", f"/tasks/{task_id}/result")
                if result["status"] in ("PUBLISHED", "PUBLISH_FAILED"):
                    break
            time.sleep(2)
        if not result:
            raise RuntimeError("Task timeout")
        record["taskStatus"] = task["status"]
        record["publishStatus"] = result["status"]
        metadata = json.loads(result["resultMetadata"])
        record["resultMetadata"] = metadata
        download = api("GET", f"/tasks/{task_id}/result/download-url")
        # 预签名 URL 不写入日志或记录。
        response = public_session.get(download["url"], timeout=60)
        if response.status_code != 200:
            raise RuntimeError(f"Result download: HTTP {response.status_code}")
        result_path = args.output_dir / "ndvi.tif"
        result_path.write_bytes(response.content)
        with rasterio.open(args.input) as source, rasterio.open(result_path) as output:
            assert source.crs == output.crs and source.transform == output.transform
            assert source.shape == output.shape and output.count == 1
            red = source.read(3, masked=True).astype("float64").filled(np.nan)
            nir = source.read(4, masked=True).astype("float64").filled(np.nan)
            if source.nodatavals[2] is not None:
                red[red == source.nodatavals[2]] = np.nan
            if source.nodatavals[3] is not None:
                nir[nir == source.nodatavals[3]] = np.nan
            valid = np.isfinite(red) & np.isfinite(nir) & (np.abs(nir + red) > 1e-6)
            expected = (nir[valid] - red[valid]) / (nir[valid] + red[valid])
            actual = output.read(1, masked=True)
            np.testing.assert_array_equal(~np.ma.getmaskarray(actual), valid)
            np.testing.assert_allclose(actual.data[valid], expected, atol=1e-6, rtol=1e-6)
            stats = metadata["statistics"]
            if manifest.get("expectedNdviRange"):
                lower, upper = manifest["expectedNdviRange"]
                assert lower <= stats["min"] <= stats["max"] <= upper, "Sample quality range failed"
                record["sampleQualityRange"] = "passed"
            assert stats["totalPixelCount"] == red.size
            assert stats["validPixelCount"] == int(valid.sum())
            assert stats["invalidPixelCount"] == int((~valid).sum())
            np.testing.assert_allclose(
                [stats["min"], stats["max"], stats["mean"], stats["validPixelRatio"]],
                [expected.min(), expected.max(), expected.mean(), valid.mean()], atol=1e-6, rtol=1e-6)
            error = np.abs(actual.data[valid] - expected)
            record["numericComparison"] = {
                "reference": "independent float64 expression, not ArcGIS",
                "maxAbsoluteError": float(error.max()),
                "meanAbsoluteError": float(error.mean()),
            }
            bounds = transform_bounds(output.crs, "EPSG:4326", *output.bounds)
        assert result["status"] == "PUBLISHED", "Map publishing did not succeed"
        map_response = public_session.get(args.wms_url, params={
            "service": "WMS", "version": "1.1.1", "request": "GetMap",
            "layers": result["workspace"] + ":" + result["layerName"],
            "styles": "", "srs": "EPSG:4326", "bbox": ",".join(map(str, bounds)),
            "width": 256, "height": 256, "format": "image/png", "transparent": "true",
        }, timeout=60)
        assert map_response.status_code == 200 and map_response.content.startswith(b"\x89PNG"), "Invalid WMS response"
        with Image.open(io.BytesIO(map_response.content)) as rendered:
            assert rendered.size == (256, 256)
            assert rendered.convert("RGBA").getchannel("A").getextrema()[1] > 0, "Empty WMS image"
        (args.output_dir / "wms.png").write_bytes(map_response.content)
        record["wms"] = "passed"
        record["status"] = "passed"
        print(f"PASS imageId={image['id']} taskId={task_id}; numeric comparison and WMS passed")
    except Exception as error:
        record["status"] = "failed"
        record["errorType"] = type(error).__name__
        # 不输出任意 HTTP 异常（可能包含带签名的 URL）。
        print(f"FAILED ({type(error).__name__}); inspect task status and safe record")
        raise SystemExit(1) from None
    finally:
        record["finishedAt"] = datetime.now(timezone.utc).isoformat()
        (args.output_dir / "verification.json").write_text(
            json.dumps(record, indent=2, allow_nan=False), encoding="utf-8")
        if signed_in:
            try:
                api("POST", "/auth/logout")
            except Exception:
                print("Logout could not be confirmed; token was not saved")


if __name__ == "__main__":
    main()
