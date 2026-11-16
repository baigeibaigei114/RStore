package com.remotesensing.platform.controller;

import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.service.PublicImageService;
import com.remotesensing.platform.vo.PublicImageVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/public/images")
public class PublicImageController {
    private final PublicImageService images;

    public PublicImageController(PublicImageService images) {
        this.images = images;
    }

    @GetMapping
    public ResponseEntity<Result<PageResult<PublicImageVO>>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(Result.success(images.page(keyword, pageNum, pageSize)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Result<PublicImageVO>> detail(@PathVariable Long id, HttpServletRequest request) {
        PublicImageVO image = images.detail(id, request.getRemoteAddr());
        if (image == null) {
            return ResponseEntity.status(404).header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                    .body(Result.fail(404, "未找到影像"));
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(Result.success(image));
    }
}
