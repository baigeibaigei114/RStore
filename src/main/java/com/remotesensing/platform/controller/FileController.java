package com.remotesensing.platform.controller;

import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.service.MinioService;
import com.remotesensing.platform.vo.FilePresignedUrlVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/files")
public class FileController {

    private final MinioService minioService;

    public FileController(MinioService minioService) {
        this.minioService = minioService;
    }

    /**
     * 为私有 bucket 中的对象生成临时访问链接，前端无需接触 MinIO 密钥。
     */
    @GetMapping("/presigned-url")
    public Result<FilePresignedUrlVO> generatePresignedUrl(@RequestParam String objectKey) {
        return Result.success(minioService.generatePresignedUrl(objectKey));
    }
}
