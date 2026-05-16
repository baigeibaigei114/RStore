package com.remotesensing.platform.controller;

import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.dto.RsImageCreateDTO;
import com.remotesensing.platform.dto.RsImageSearchDTO;
import com.remotesensing.platform.dto.RsImageVisibilityUpdateDTO;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.RsImageService;
import com.remotesensing.platform.vo.RsImageListVO;
import com.remotesensing.platform.vo.RsImageVO;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/images")
public class RsImageController {

    private static final BigDecimal MIN_LNG = BigDecimal.valueOf(-180);
    private static final BigDecimal MAX_LNG = BigDecimal.valueOf(180);
    private static final BigDecimal MIN_LAT = BigDecimal.valueOf(-90);
    private static final BigDecimal MAX_LAT = BigDecimal.valueOf(90);

    private final RsImageService imageService;

    public RsImageController(RsImageService imageService) {
        this.imageService = imageService;
    }

    @PostMapping
    public Result<RsImageVO> create(@Valid @RequestBody RsImageCreateDTO createDTO) {
        return Result.success(imageService.create(createDTO));
    }

    /**
     * GeoTIFF 上传入口：文件本体进入 MinIO，元数据入库，缩略图由后台线程异步生成。
     */
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

    @GetMapping("/search")
    public Result<PageResult<RsImageListVO>> search(@RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false)
                                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                                    OffsetDateTime startTime,
                                                    @RequestParam(required = false)
                                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                                    OffsetDateTime endTime,
                                                    @RequestParam(required = false) String sensor,
                                                    @RequestParam(required = false) BigDecimal maxCloudPercent,
                                                    @RequestParam(required = false) String bbox,
                                                    @RequestParam(required = false) Integer pageNum,
                                                    @RequestParam(required = false) Integer pageSize) {
        RsImageSearchDTO query = new RsImageSearchDTO();
        query.setKeyword(keyword);
        query.setStartTime(startTime);
        query.setEndTime(endTime);
        query.setSensor(sensor);
        query.setMaxCloudPercent(maxCloudPercent);
        parseBbox(query, bbox);
        return Result.success(imageService.search(query, pageNum, pageSize));
    }

    @GetMapping("/search-by-region")
    public Result<PageResult<RsImageListVO>> searchByRegion(@RequestParam Long regionId,
                                                            @RequestParam(required = false)
                                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                                            OffsetDateTime startTime,
                                                            @RequestParam(required = false)
                                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                                            OffsetDateTime endTime,
                                                            @RequestParam(required = false) String sensor,
                                                            @RequestParam(required = false) BigDecimal maxCloudPercent,
                                                            @RequestParam(required = false) Integer pageNum,
                                                            @RequestParam(required = false) Integer pageSize) {
        RsImageSearchDTO query = new RsImageSearchDTO();
        query.setRegionId(regionId);
        query.setStartTime(startTime);
        query.setEndTime(endTime);
        query.setSensor(sensor);
        query.setMaxCloudPercent(maxCloudPercent);
        return Result.success(imageService.searchByRegion(query, pageNum, pageSize));
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteById(@PathVariable Long id) {
        imageService.deleteById(id);
        return Result.success();
    }

    @PatchMapping("/{id}/visibility")
    public Result<RsImageVO> updateVisibility(@PathVariable Long id,
                                              @Valid @RequestBody RsImageVisibilityUpdateDTO updateDTO) {
        return Result.success(imageService.updateVisibility(id, updateDTO.getVisibility()));
    }

    private void parseBbox(RsImageSearchDTO query, String bbox) {
        if (bbox == null || bbox.isBlank()) {
            return;
        }
        String[] values = bbox.split(",");
        if (values.length != 4) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "bbox 格式错误，应为 minLng,minLat,maxLng,maxLat");
        }
        try {
            query.setMinLng(new BigDecimal(values[0].trim()));
            query.setMinLat(new BigDecimal(values[1].trim()));
            query.setMaxLng(new BigDecimal(values[2].trim()));
            query.setMaxLat(new BigDecimal(values[3].trim()));
        } catch (NumberFormatException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "bbox 坐标必须是数字");
        }
        if (query.getMinLng().compareTo(query.getMaxLng()) >= 0
                || query.getMinLat().compareTo(query.getMaxLat()) >= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "bbox 最小经纬度必须小于最大经纬度");
        }
        // footprint 统一使用 EPSG:4326，经纬度越界会让空间查询结果失真。
        if (query.getMinLng().compareTo(MIN_LNG) < 0
                || query.getMaxLng().compareTo(MAX_LNG) > 0
                || query.getMinLat().compareTo(MIN_LAT) < 0
                || query.getMaxLat().compareTo(MAX_LAT) > 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "bbox 经度范围应为 [-180,180]，纬度范围应为 [-90,90]");
        }
        query.setHasBbox(true);
    }
}
