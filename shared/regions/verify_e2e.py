"""区域 NDVI 服务验收：创建影像、区域和任务；保留测试数据，不修改历史资产。"""
import argparse
import json
import os
import time
from pathlib import Path
from uuid import uuid4

import numpy as np
import rasterio
from rasterio.warp import transform
import requests


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--base-url", default="http://[::1]:8080/api")
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=False)
    record = {"status": "running", "checks": []}
    session = requests.Session()
    session.trust_env = False
    authenticated = False

    def api(method, path, reject=False, **kwargs):
        response = session.request(method, args.base_url + path, timeout=90, **kwargs)
        response.raise_for_status()
        body = response.json()
        if body.get("code") == 429:
            raise RuntimeError("Rate limited; wait before rerunning, do not clear Redis")
        if reject:
            assert body.get("code") != 200, f"Expected rejection: {path}"
            return None
        assert body.get("code") == 200, f"API failed: {path}"
        return body["data"]

    def polygon(points):
        with rasterio.open(args.input) as source:
            world = [source.transform * point for point in points]
            xs, ys = transform(source.crs, "EPSG:4326", *zip(*world))
        return {"type": "Polygon", "coordinates": [list(map(list, zip(xs, ys)))]}

    def await_task(task_id, expected):
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            task = api("GET", f"/tasks/{task_id}")
            if task["status"] in ("SUCCESS", "FAILED", "CANCELED"):
                assert task["status"] == expected, f"Unexpected terminal state: task {task_id}"
                return task
            time.sleep(2)
        raise TimeoutError(f"Task {task_id} did not finish")

    try:
        login = api("POST", "/auth/login", json={"username": os.environ["BASELINE_USERNAME"],
                                                "password": os.environ["BASELINE_PASSWORD"]})
        session.headers["Authorization"] = "Bearer " + login["accessToken"]
        authenticated = True
        with args.input.open("rb") as image_file, args.manifest.open("rb") as manifest_file:
            image = api("POST", "/images/upload-standardized",
                        files={"file": (args.input.name, image_file, "image/tiff"),
                               "manifest": ("manifest.json", manifest_file, "application/json")},
                        data={"name": "regional-e2e-" + uuid4().hex[:8]})
        record["imageId"] = image["id"]
        with rasterio.open(args.input) as source:
            width, height = source.width, source.height
        # 半幅宽高的规则矩形：独立预期掩膜由数组索引生成，不调用生产区域掩膜函数。
        right, bottom = width // 2, height // 2
        geometry = polygon([(0, 0), (right, 0), (right, bottom), (0, bottom), (0, 0)])
        region = api("POST", "/monitoring-regions", json={"name": "E2E 原始区域", "geometry": geometry})
        region_id = region["id"]
        record["regionId"] = region_id
        assert region["version"] == 1
        assert any(item["id"] == region_id for item in api("GET", "/monitoring-regions"))
        api("POST", "/monitoring-regions", reject=True, json={
            "name": "自交拒绝", "geometry": polygon([(0, 0), (4, 4), (0, 4), (4, 0), (0, 0)])})
        record["checks"].append("region create/list and PostGIS self-intersection rejection")
        request = {"imageId": image["id"], "taskType": "NDVI", "monitoringRegionId": region_id,
                   "clientRequestId": "region-" + uuid4().hex,
                   "params": {"redBand": 3, "nirBand": 4, "regionSnapshot": {"name": "forged"}}}
        task_id = api("POST", "/tasks", json=request)["taskId"]
        record["taskId"] = task_id
        assert api("POST", "/tasks", json=request)["taskId"] == task_id
        task = await_task(task_id, "SUCCESS")
        snapshot = json.loads(task["params"])["regionSnapshot"]
        assert snapshot["name"] == region["name"] and snapshot["version"] == 1
        result = api("GET", f"/tasks/{task_id}/result")
        metadata = json.loads(result["resultMetadata"])
        assert metadata["schemaVersion"] == 2 and metadata["scope"] == "monitoring_region"
        assert metadata["regionSnapshot"] == snapshot and metadata["coverageRatio"] == 1
        download = api("GET", f"/tasks/{task_id}/result/download-url")
        with requests.Session() as public:
            public.trust_env = False
            response = public.get(download["url"], timeout=60)
            response.raise_for_status()
        # 不记录签名 URL 或令牌，只保留结果影像和非敏感验收记录。
        (args.output_dir / "regional-ndvi.tif").write_bytes(response.content)
        with rasterio.open(args.input) as source, rasterio.MemoryFile(response.content) as memory:
            with memory.open() as output:
                red = source.read(3, masked=True).astype("float64").filled(np.nan)
                nir = source.read(4, masked=True).astype("float64").filled(np.nan)
                if source.nodatavals[2] is not None:
                    red[red == source.nodatavals[2]] = np.nan
                if source.nodatavals[3] is not None:
                    nir[nir == source.nodatavals[3]] = np.nan
                inside = np.zeros(red.shape, dtype=bool)
                inside[:bottom, :right] = True
                valid = inside & np.isfinite(red) & np.isfinite(nir) & (np.abs(red + nir) > 1e-6)
                expected = ((nir[valid] - red[valid]) / (nir[valid] + red[valid])).astype("float32")
                actual = output.read(1)
                np.testing.assert_array_equal(np.isfinite(actual), valid)
                np.testing.assert_allclose(actual[valid], expected, atol=1e-6)
                assert source.crs == output.crs and source.transform == output.transform
                assert source.shape == output.shape
                assert output.tags().get("region_snapshot_sha256")
                stats = metadata["statistics"]
                assert stats["totalPixelCount"] == right * bottom
                assert stats["validPixelCount"] == int(valid.sum())
                assert stats["invalidPixelCount"] == right * bottom - int(valid.sum())
                np.testing.assert_allclose(stats["validPixelRatio"], valid.sum() / (right * bottom))
                np.testing.assert_allclose([stats["min"], stats["max"], stats["mean"]],
                                           [expected.min(), expected.max(), expected.mean(dtype="float64")], atol=1e-7)
        record["statistics"] = stats
        record["checks"].append("idempotency, server snapshot, real worker, independent mask/statistics/grid comparison")
        changed = api("PUT", f"/monitoring-regions/{region_id}", json={
            "name": "E2E 修改后区域", "geometry": polygon([(0, 0), (1, 0), (1, 1), (0, 1), (0, 0)])})
        assert changed["version"] == 2
        assert json.loads(api("GET", f"/tasks/{task_id}")["params"])["regionSnapshot"] == snapshot
        record["checks"].append("region edit does not change historical task geometry/name/version")
        partial = api("POST", "/monitoring-regions", json={
            "name": "E2E 部分覆盖拒绝", "geometry": polygon([(-2, 0), (2, 0), (2, 2), (-2, 2), (-2, 0)])})
        request.update(monitoringRegionId=partial["id"], clientRequestId="region-" + uuid4().hex)
        failed_id = api("POST", "/tasks", json=request)["taskId"]
        record["partialCoverageTaskId"] = failed_id
        failed = await_task(failed_id, "FAILED")
        assert "fully covered" in failed.get("errorMessage", "")
        record["checks"].append("partial coverage fails explicitly rather than reporting partial-region statistics")
        record["status"] = "passed"
        print(f"PASS image={image['id']} region={region_id} task={task_id} rejectedTask={failed_id}")
    except Exception as error:
        record.update(status="failed", errorType=type(error).__name__)
        raise
    finally:
        if authenticated:
            try:
                api("POST", "/auth/logout")
            except (requests.RequestException, AssertionError, RuntimeError):
                pass
        session.close()
        (args.output_dir / "verification.json").write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
