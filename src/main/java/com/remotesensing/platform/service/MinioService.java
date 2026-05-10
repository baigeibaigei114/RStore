package com.remotesensing.platform.service;

import com.remotesensing.platform.vo.MinioUploadVO;
import com.remotesensing.platform.vo.FilePresignedUrlVO;
import java.nio.file.Path;

public interface MinioService {

    /**
     * 上传已经保存到本地临时目录的 GeoTIFF，避免重复读取 Multipart 流。
     */
    MinioUploadVO uploadGeoTiff(Path filePath, String originalFilename, String contentType);

    /**
     * 上传本地生成的处理结果文件，例如缩略图。
     */
    MinioUploadVO uploadLocalFile(Path filePath, String objectKey, String contentType);

    /**
     * 删除对象存储文件，用于数据库失败补偿或影像删除后的空间回收。
     */
    void deleteObject(String objectKey);

    /**
     * 为私有对象生成短期可访问 URL，不暴露服务端密钥。
     */
    FilePresignedUrlVO generatePresignedUrl(String objectKey);
}
