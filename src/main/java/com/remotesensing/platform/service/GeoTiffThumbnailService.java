package com.remotesensing.platform.service;

import org.springframework.web.multipart.MultipartFile;

public interface GeoTiffThumbnailService {

    /**
     * 基于已入库的 imageId 生成固定路径的 PNG 缩略图，并上传到 MinIO。
     */
    String generateAndUpload(MultipartFile file, Long imageId);
}
