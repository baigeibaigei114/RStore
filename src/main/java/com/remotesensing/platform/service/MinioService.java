package com.remotesensing.platform.service;

import com.remotesensing.platform.vo.MinioUploadVO;
import com.remotesensing.platform.vo.FilePresignedUrlVO;
import java.nio.file.Path;

/**
 * MinIO 对象存储服务接口。
 * <p>
 * 提供文件上传、下载、删除和预签名 URL 生成功能。
 * 所有文件操作均通过 MinioClient 完成，业务层不直接接触 MinIO 客户端。
 */
public interface MinioService {

    /**
     * 上传已经保存到本地临时目录的 GeoTIFF，避免重复读取 Multipart 流。
     *
     * @param filePath         本地文件路径
     * @param originalFilename 原始文件名（用于生成对象键）
     * @param contentType      文件 MIME 类型
     * @return 上传结果（存储桶、对象键、文件大小、MIME 类型）
     */
    MinioUploadVO uploadGeoTiff(Path filePath, String originalFilename, String contentType);

    /**
     * 上传本地生成的处理结果文件，例如缩略图。
     *
     * @param filePath    本地文件路径
     * @param objectKey   目标对象键（已预定义的存储路径）
     * @param contentType 文件 MIME 类型
     * @return 上传结果
     */
    MinioUploadVO uploadLocalFile(Path filePath, String objectKey, String contentType);

    /**
     * 下载对象到本地临时文件，供异步缩略图或后续处理节点使用。
     *
     * @param objectKey  MinIO 对象键
     * @param targetPath 本地目标文件路径
     */
    void downloadObject(String objectKey, Path targetPath);

    /**
     * 删除对象存储文件，用于数据库失败补偿或影像删除后的空间回收。
     *
     * @param objectKey MinIO 对象键
     */
    void deleteObject(String objectKey);

    /**
     * 为私有对象生成短期可访问 URL，不暴露服务端密钥。
     *
     * @param objectKey MinIO 对象键
     * @return 预签名 URL 信息（URL、过期时间等）
     */
    FilePresignedUrlVO generatePresignedUrl(String objectKey);
}
