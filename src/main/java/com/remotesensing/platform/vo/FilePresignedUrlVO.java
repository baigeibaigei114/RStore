package com.remotesensing.platform.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 文件预签名 URL 响应 VO。
 * 用于返回 MinIO 生成的临时访问链接，前端可直接使用该 URL 下载或预览文件。
 */
@Data
@AllArgsConstructor
public class FilePresignedUrlVO {

    /** 文件在 MinIO 中的对象键（objectKey），对应业务记录中的存储路径。 */
    private String objectKey;

    /** 预签名临时访问 URL，包含签名参数，在 expireSeconds 内有效。 */
    private String url;

    /** URL 有效时长（秒），过期后需重新生成。 */
    private Integer expireSeconds;
}
