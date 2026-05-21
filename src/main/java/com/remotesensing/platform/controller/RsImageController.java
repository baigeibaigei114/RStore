package com.remotesensing.platform.controller;

import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.dto.RsImageCreateDTO;
import com.remotesensing.platform.dto.RsImageSearchDTO;
import com.remotesensing.platform.dto.RsImageVisibilityUpdateDTO;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.RsImageService;
import com.remotesensing.platform.vo.FilePresignedUrlVO;
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

/**
 * 遥感影像管理控制器。
 * <p>提供遥感影像的增删改查、文件上传、空间搜索、可见性控制等功能。
 * 影像文件存储于 MinIO，元数据记录在业务数据库，空间索引借助 PostGIS 实现。</p>
 */
@RestController
@RequestMapping("/images")
public class RsImageController {

    /** WGS84 坐标系下有效经度下限，超出该范围的空间查询无意义。 */
    private static final BigDecimal MIN_LNG = BigDecimal.valueOf(-180);
    /** WGS84 坐标系下有效经度上限。 */
    private static final BigDecimal MAX_LNG = BigDecimal.valueOf(180);
    /** WGS84 坐标系下有效纬度下限。 */
    private static final BigDecimal MIN_LAT = BigDecimal.valueOf(-90);
    /** WGS84 坐标系下有效纬度上限。 */
    private static final BigDecimal MAX_LAT = BigDecimal.valueOf(90);

    private final RsImageService imageService;

    public RsImageController(RsImageService imageService) {
        this.imageService = imageService;
    }

    /**
     * 创建影像记录（仅元数据，不含文件上传）。
     * <p>适用于已存在文件、仅需注册元数据的场景。DTO 经过 Bean Validation 校验，
     * 避免无效数据进入持久层。</p>
     *
     * @param createDTO 影像创建参数（名称、传感器、拍摄时间等）
     * @return 创建成功的影像完整信息 VO
     */
    @PostMapping
    public Result<RsImageVO> create(@Valid @RequestBody RsImageCreateDTO createDTO) {
        return Result.success(imageService.create(createDTO));
    }

    /**
     * GeoTIFF 上传入口：文件本体进入 MinIO，元数据入库，缩略图由后台线程异步生成。
     * <p>上传为同步请求 + 异步后处理模式：文件流写入 MinIO 后立即返回，
     * 缩略图生成和空间索引构建在独立线程中执行，避免阻塞上传响应。</p>
     *
     * @param file        上传的 GeoTIFF 文件
     * @param name        影像名称
     * @param sensor      传感器类型（可选），如 Sentinel-2、Landsat 8
     * @param captureTime 拍摄时间（可选），ISO-8601 格式
     * @param cloudPercent 云量百分比（可选），用于后续质量筛选
     * @return 上传成功后的影像完整信息 VO
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

    /**
     * 根据 ID 查询单条影像详细信息。
     *
     * @param id 影像主键 ID
     * @return 影像详细信息 VO，包含元数据与空间范围
     */
    @GetMapping("/{id}")
    public Result<RsImageVO> getById(@PathVariable Long id) {
        return Result.success(imageService.getById(id));
    }

    /**
     * 获取影像文件下载地址（预签名 URL）。
     * <p>返回 MinIO 预签名 URL，前端可直接用于下载，避免暴露存储服务密钥。
     * URL 具有时效性，过期后需重新生成。</p>
     *
     * @param id 影像主键 ID
     * @return 包含预签名 URL 的 VO
     */
    @GetMapping("/{id}/download-url")
    public Result<FilePresignedUrlVO> getDownloadUrl(@PathVariable Long id) {
        return Result.success(imageService.getDownloadUrl(id));
    }

    /**
     * 获取影像缩略图访问地址（预签名 URL）。
     * <p>缩略图在上传后由后台线程异步生成，若尚未生成完毕可能返回空。
     * 同样使用预签名 URL 保护存储服务安全。</p>
     *
     * @param id 影像主键 ID
     * @return 包含缩略图预签名 URL 的 VO
     */
    @GetMapping("/{id}/thumbnail-url")
    public Result<FilePresignedUrlVO> getThumbnailUrl(@PathVariable Long id) {
        return Result.success(imageService.getThumbnailUrl(id));
    }

    /**
     * 分页查询影像列表（简单模式）。
     * <p>不包含复杂筛选条件，适用于首页概览场景。</p>
     *
     * @param pageNum  页码（从 1 开始），为空则使用默认值
     * @param pageSize 每页条数，为空则使用默认值
     * @return 分页结果，包含影像摘要信息列表
     */
    @GetMapping
    public Result<PageResult<RsImageVO>> page(@RequestParam(required = false) Integer pageNum,
                                              @RequestParam(required = false) Integer pageSize) {
        return Result.success(imageService.page(pageNum, pageSize));
    }

    /**
     * 高级搜索：按关键字、时间范围、传感器、云量、空间范围等多维度筛选影像。
     * <p>bbox 参数需解析后再执行空间查询，提前对经纬度进行合法性校验，
     * 防止非法空间范围导致数据库索引扫描异常或结果失真。</p>
     *
     * @param keyword        搜索关键字（匹配名称等文本字段）
     * @param startTime      拍摄时间范围起始，ISO-8601 格式
     * @param endTime        拍摄时间范围结束，ISO-8601 格式
     * @param sensor         传感器类型筛选
     * @param maxCloudPercent 最大云量百分比筛选
     * @param bbox           空间范围，格式 "minLng,minLat,maxLng,maxLat"（EPSG:4326）
     * @param pageNum        页码
     * @param pageSize       每页条数
     * @return 分页结果，包含匹配条件的影像列表
     */
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
        // 将前端传入的逗号分隔 bbox 字符串解析为结构化坐标，同时校验合法性
        parseBbox(query, bbox);
        return Result.success(imageService.search(query, pageNum, pageSize));
    }

    /**
     * 按行政区划查询影像。
     * <p>通过 regionId 关联行政区边界，查询覆盖或部分覆盖该区域的影像。
     * 适用于按省/市/县筛选影像的业务场景。</p>
     *
     * @param regionId       行政区划 ID
     * @param startTime      拍摄时间范围起始
     * @param endTime        拍摄时间范围结束
     * @param sensor         传感器类型筛选
     * @param maxCloudPercent 最大云量百分比筛选
     * @param pageNum        页码
     * @param pageSize       每页条数
     * @return 分页结果，包含覆盖指定区域的影像列表
     */
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

    /**
     * 根据 ID 删除影像。
     * <p>逻辑删除或物理删除取决于 Service 层实现。关联的 MinIO 文件
     * 和缩略图是否一并清理也由 Service 层策略决定。</p>
     *
     * @param id 待删除的影像主键 ID
     * @return 统一响应结果
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteById(@PathVariable Long id) {
        imageService.deleteById(id);
        return Result.success();
    }

    /**
     * 更新影像可见性状态。
     * <p>控制影像在前端是否展示，用于审核或下架场景。
     * 通过 {@link Valid} 校验请求体，防止非法状态值写入数据库。</p>
     *
     * @param id       影像主键 ID
     * @param updateDTO 包含新可见性状态的 DTO
     * @return 更新后的影像完整信息 VO
     */
    @PatchMapping("/{id}/visibility")
    public Result<RsImageVO> updateVisibility(@PathVariable Long id,
                                              @Valid @RequestBody RsImageVisibilityUpdateDTO updateDTO) {
        return Result.success(imageService.updateVisibility(id, updateDTO.getVisibility()));
    }

    /**
     * 解析 bbox 字符串为结构化坐标并校验合法性。
     * <p>bbox 格式为 "minLng,minLat,maxLng,maxLat"，EPSG:4326 坐标系。
     * 校验顺序：格式 -> 数值类型 -> 大小关系 -> 世界范围，
     * 确保进入空间查询引擎的坐标一定是合理值，避免数据库空间索引被异常输入破坏。</p>
     *
     * @param query 影像搜索 DTO，解析结果直接写入该对象
     * @param bbox  前端传入的逗号分隔经纬度字符串，可能为 null 或空
     */
    private void parseBbox(RsImageSearchDTO query, String bbox) {
        // bbox 为可选参数，不传时不进行空间筛选
        if (bbox == null || bbox.isBlank()) {
            return;
        }
        // 严格要求四段式格式：minLng,minLat,maxLng,maxLat，防止 SQL/索引注入
        String[] values = bbox.split(",");
        if (values.length != 4) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "bbox 格式错误，应为 minLng,minLat,maxLng,maxLat");
        }
        try {
            // 使用 BigDecimal 而非 double，避免浮点数精度丢失导致空间边界判断偏差
            query.setMinLng(new BigDecimal(values[0].trim()));
            query.setMinLat(new BigDecimal(values[1].trim()));
            query.setMaxLng(new BigDecimal(values[2].trim()));
            query.setMaxLat(new BigDecimal(values[3].trim()));
        } catch (NumberFormatException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "bbox 坐标必须是数字");
        }
        // 防止反向范围（最小值大于最大值）导致空间查询结果异常
        if (query.getMinLng().compareTo(query.getMaxLng()) >= 0
                || query.getMinLat().compareTo(query.getMaxLat()) >= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "bbox 最小经纬度必须小于最大经纬度");
        }
        // 所有空间查询使用 EPSG:4326，超界坐标会使 PostGIS 的 ST_Intersects/ST_Within 返回不可靠结果甚至报错
        if (query.getMinLng().compareTo(MIN_LNG) < 0
                || query.getMaxLng().compareTo(MAX_LNG) > 0
                || query.getMinLat().compareTo(MIN_LAT) < 0
                || query.getMaxLat().compareTo(MAX_LAT) > 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "bbox 经度范围应为 [-180,180]，纬度范围应为 [-90,90]");
        }
        query.setHasBbox(true);
    }
}
