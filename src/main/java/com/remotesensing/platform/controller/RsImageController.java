package com.remotesensing.platform.controller;

import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.dto.RsImageCreateDTO;
import com.remotesensing.platform.service.RsImageService;
import com.remotesensing.platform.vo.RsImageVO;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/images")
public class RsImageController {

    private final RsImageService imageService;

    public RsImageController(RsImageService imageService) {
        this.imageService = imageService;
    }

    @PostMapping
    public Result<RsImageVO> create(@Valid @RequestBody RsImageCreateDTO createDTO) {
        return Result.success(imageService.create(createDTO));
    }

    @PostMapping("/upload")
    public Result<RsImageVO> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam("name") String name,
                                    @RequestParam(value = "sensor", required = false) String sensor,
                                    @RequestParam(value = "captureTime", required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                    OffsetDateTime captureTime,
                                    @RequestParam(value = "cloudPercent", required = false) BigDecimal cloudPercent) {
        return Result.success(imageService.upload(file, name, sensor, captureTime, cloudPercent));
    }

    @GetMapping("/{id}")
    public Result<RsImageVO> getById(@PathVariable Long id) {
        return Result.success(imageService.getById(id));
    }

    @GetMapping
    public Result<PageResult<RsImageVO>> page(@RequestParam(required = false) Integer pageNum,
                                              @RequestParam(required = false) Integer pageSize) {
        return Result.success(imageService.page(pageNum, pageSize));
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteById(@PathVariable Long id) {
        imageService.deleteById(id);
        return Result.success();
    }
}
