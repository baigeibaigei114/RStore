package com.remotesensing.platform.service;

import java.nio.file.Path;

public interface GeoTiffThumbnailService {

    /**
     * 基于本地临时 GeoTIFF 生成缩略图，上传链路可复用同一份临时文件。
     */
    String generateAndUpload(Path inputFile, Long imageId);
}
