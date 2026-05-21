package com.remotesensing.platform.controller;

import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.service.MinioService;
import com.remotesensing.platform.vo.FilePresignedUrlVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件访问控制器。
 * <p>为前端提供 MinIO 存储文件的临时访问凭证（预签名 URL），
 * 避免前端直接接触 MinIO 访问密钥，所有文件操作均通过后端鉴权后转发。</p>
 */
@RestController
@RequestMapping("/files")
public class FileController {

    private final MinioService minioService;

    public FileController(MinioService minioService) {
        this.minioService = minioService;
    }

    /**
     * 为私有 bucket 中的对象生成临时访问链接，前端无需接触 MinIO 密钥。
     * <p>预签名 URL 具有过期时间（通常在 Service 层配置），
     * 即使 URL 泄露，攻击者在过期后也无法访问。每次访问请求都应重新生成，
     * 而非长期缓存，以保持访问控制的有效性。</p>
     *
     * @param objectKey MinIO 中的对象存储路径
     * @return 包含预签名 URL 及其过期时间的 VO
     */
    @GetMapping("/presigned-url")
    public Result<FilePresignedUrlVO> generatePresignedUrl(@RequestParam String objectKey) {
        return Result.success(minioService.generatePresignedUrl(objectKey));
    }
}
