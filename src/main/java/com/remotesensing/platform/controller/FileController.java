package com.remotesensing.platform.controller;

import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.config.properties.RateLimitProperties;
import com.remotesensing.platform.service.MinioService;
import com.remotesensing.platform.service.RateLimitService;
import com.remotesensing.platform.vo.FilePresignedUrlVO;
import java.time.Duration;
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
    private final RateLimitService rateLimitService;
    private final RateLimitProperties rateLimitProperties;
    private final CurrentUserContext currentUserContext;

    public FileController(MinioService minioService,
                          RateLimitService rateLimitService,
                          RateLimitProperties rateLimitProperties,
                          CurrentUserContext currentUserContext) {
        this.minioService = minioService;
        this.rateLimitService = rateLimitService;
        this.rateLimitProperties = rateLimitProperties;
        this.currentUserContext = currentUserContext;
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
        checkPresignedUrlRateLimit();
        return Result.success(minioService.generatePresignedUrl(objectKey));
    }

    /**
     * 预签名 URL 获取限流，按用户维度控制。
     *
     * <p>预签名 URL 每次调用都会触发 MinIO API，高频请求会耗尽 MinIO 连接池，
     * 因此限制单用户每 60 秒最多获取的次数。</p>
     */
    private void checkPresignedUrlRateLimit() {
        String userId = currentUserContext.getCurrentUserId();
        rateLimitService.check(
                "presigned-url:user:" + userId,
                rateLimitProperties.getPresignedUrlLimit(),
                Duration.ofSeconds(rateLimitProperties.getPresignedUrlWindowSeconds())
        );
    }
}
