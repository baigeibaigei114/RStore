package com.remotesensing.platform.service;

import java.nio.file.Path;

/**
 * GeoTIFF 缩略图生成服务接口。
 * <p>
 * 基于已保存到本地的 GeoTIFF 文件，通过 Python 脚本生成预览缩略图，
 * 并将生成的缩略图上传到 MinIO 对象存储。
 */
public interface GeoTiffThumbnailService {

    /**
     * 基于本地临时 GeoTIFF 生成缩略图，并上传到指定对象路径。
     *
     * @param inputFile          本地 GeoTIFF 文件路径
     * @param thumbnailObjectKey 缩略图在 MinIO 中的对象键
     * @return 缩略图的 MinIO 对象键（与入参相同，便于链式调用）
     */
    String generateAndUpload(Path inputFile, String thumbnailObjectKey);
}
