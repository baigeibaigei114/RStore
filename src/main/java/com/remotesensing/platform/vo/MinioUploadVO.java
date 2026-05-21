package com.remotesensing.platform.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * MinIO 文件上传结果 VO。
 * 用于返回文件上传到 MinIO 后的存储信息，包含存储桶、对象键及文件属性。
 */
@Data
@AllArgsConstructor
public class MinioUploadVO {

    /** 文件存储的 MinIO 存储桶名称。 */
    private String bucketName;

    /** 文件在 MinIO 中的对象键（objectKey），用于后续访问和引用。 */
    private String objectKey;

    /** 文件大小（字节）。 */
    private Long fileSize;

    /** 文件 MIME 类型，如 "image/tiff"、"image/png"。 */
    private String contentType;
}
