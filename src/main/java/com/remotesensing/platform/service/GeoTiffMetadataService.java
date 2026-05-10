package com.remotesensing.platform.service;

import com.remotesensing.platform.vo.GeoTiffMetadataVO;
import java.nio.file.Path;

public interface GeoTiffMetadataService {

    /**
     * 解析已经落到本地临时目录的 GeoTIFF，避免大文件被 Multipart 流重复复制。
     */
    GeoTiffMetadataVO parse(Path filePath);
}
