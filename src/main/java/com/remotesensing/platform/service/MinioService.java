package com.remotesensing.platform.service;

import com.remotesensing.platform.vo.MinioUploadVO;
import com.remotesensing.platform.vo.FilePresignedUrlVO;
import org.springframework.web.multipart.MultipartFile;

public interface MinioService {

    MinioUploadVO uploadGeoTiff(MultipartFile file);

    FilePresignedUrlVO generatePresignedUrl(String objectKey);
}
