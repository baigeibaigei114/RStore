package com.remotesensing.platform.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MinioUploadVO {

    private String bucketName;
    private String objectKey;
    private Long fileSize;
    private String contentType;
}
