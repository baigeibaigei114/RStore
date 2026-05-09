package com.remotesensing.platform.service;

import com.remotesensing.platform.vo.MinioUploadVO;
import com.remotesensing.platform.vo.FilePresignedUrlVO;
import java.nio.file.Path;
import org.springframework.web.multipart.MultipartFile;

public interface MinioService {

    /**
     * 上传原始 GeoTIFF，返回数据库需要保存的对象存储元数据。
     */
    MinioUploadVO uploadGeoTiff(MultipartFile file);

    /**
     * 上传本地生成的处理结果文件，例如缩略图。
     */
    MinioUploadVO uploadLocalFile(Path filePath, String objectKey, String contentType);

    /**
     * 为私有对象生成短期可访问 URL，不暴露服务端密钥。
     */
    FilePresignedUrlVO generatePresignedUrl(String objectKey);
}
