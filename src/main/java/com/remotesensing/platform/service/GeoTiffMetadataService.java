package com.remotesensing.platform.service;

import com.remotesensing.platform.vo.GeoTiffMetadataVO;
import org.springframework.web.multipart.MultipartFile;

public interface GeoTiffMetadataService {

    /**
     * 将上传文件交给 Python worker 解析，返回可写入 rs_image 的元数据结构。
     */
    GeoTiffMetadataVO parse(MultipartFile file);
}
