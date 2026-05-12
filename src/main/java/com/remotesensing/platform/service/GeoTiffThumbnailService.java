package com.remotesensing.platform.service;

import java.nio.file.Path;

public interface GeoTiffThumbnailService {

    /**
     * 基于本地临时 GeoTIFF 生成缩略图，并上传到指定对象路径。
     */
    String generateAndUpload(Path inputFile, String thumbnailObjectKey);
}
