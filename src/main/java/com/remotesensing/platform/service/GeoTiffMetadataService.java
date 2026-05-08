package com.remotesensing.platform.service;

import com.remotesensing.platform.vo.GeoTiffMetadataVO;
import org.springframework.web.multipart.MultipartFile;

public interface GeoTiffMetadataService {

    GeoTiffMetadataVO parse(MultipartFile file);
}
