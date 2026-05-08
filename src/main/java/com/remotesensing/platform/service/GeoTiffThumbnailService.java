package com.remotesensing.platform.service;

import org.springframework.web.multipart.MultipartFile;

public interface GeoTiffThumbnailService {

    String generateAndUpload(MultipartFile file, Long imageId);
}
