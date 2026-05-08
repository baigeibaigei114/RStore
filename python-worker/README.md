# Python Worker

该目录用于放置遥感影像处理节点脚本。当前脚本基于 `rasterio` 解析本地 GeoTIFF 元数据。

## 安装依赖

```powershell
cd python-worker
python -m venv .venv
.\.venv\Scripts\activate
pip install -r requirements.txt
```

## 运行示例

```powershell
python parse_geotiff_metadata.py D:\data\sample.tif
```

成功输出示例：

```json
{
  "success": true,
  "data": {
    "width": 1024,
    "height": 1024,
    "bandCount": 4,
    "crs": "EPSG:4326",
    "bounds": {
      "left": 116.1,
      "bottom": 39.8,
      "right": 116.5,
      "top": 40.1
    },
    "transform": [0.000390625, 0.0, 116.1, 0.0, -0.00029296875, 40.1],
    "resolution": {
      "x": 0.000390625,
      "y": 0.00029296875
    },
    "nodata": null
  }
}
```

解析失败时会输出：

```json
{
  "success": false,
  "error": "Failed to parse GeoTIFF metadata: ..."
}
```
