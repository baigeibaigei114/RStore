package com.remotesensing.platform.service;

import com.remotesensing.platform.vo.GeoTiffMetadataVO;
import java.nio.file.Path;

/**
 * GeoTIFF 元数据解析服务接口。
 * <p>
 * 通过调用 Python 脚本解析已保存到本地的 GeoTIFF 文件，
 * 提取影像的尺寸、波段数、坐标系、空间范围和分辨率等元数据。
 */
public interface GeoTiffMetadataService {

    /**
     * 解析已经落到本地临时目录的 GeoTIFF，避免大文件被 Multipart 流重复复制。
     *
     * @param filePath 本地 GeoTIFF 文件路径
     * @return 解析后的元数据信息（尺寸、波段、CRS、范围、分辨率等）
     */
    GeoTiffMetadataVO parse(Path filePath);
}
