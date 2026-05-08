package com.remotesensing.platform.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FilePresignedUrlVO {

    private String objectKey;
    private String url;
    private Integer expireSeconds;
}
