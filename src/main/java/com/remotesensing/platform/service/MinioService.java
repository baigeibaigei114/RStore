package com.remotesensing.platform.service;

import com.remotesensing.platform.vo.MinioUploadVO;
import com.remotesensing.platform.vo.FilePresignedUrlVO;
import java.nio.file.Path;
import org.springframework.web.multipart.MultipartFile;

public interface MinioService {

    MinioUploadVO uploadGeoTiff(MultipartFile file);

    MinioUploadVO uploadLocalFile(Path filePath, String objectKey, String contentType);

    FilePresignedUrlVO generatePresignedUrl(String objectKey);
}
