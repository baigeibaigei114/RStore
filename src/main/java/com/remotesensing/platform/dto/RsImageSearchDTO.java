package com.remotesensing.platform.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 遥感影像搜索查询 DTO。
 * 用于接收前端影像列表的多条件筛选请求，支持按行政区、时间范围、传感器、云量及空间边界框查询。
 */
@Data
public class RsImageSearchDTO {

    /** 行政区划 ID，用于按行政区域筛选影像，对应 admin_region 表的 id。 */
    private Long regionId;

    /** 搜索关键词，模糊匹配影像名称或编码。 */
    private String keyword;

    /** 影像采集起始时间（UTC），筛选 >= 该时间的数据。 */
    private OffsetDateTime startTime;

    /** 影像采集结束时间（UTC），筛选 <= 该时间的数据。 */
    private OffsetDateTime endTime;

    /** 传感器类型筛选，如 "MSI"、"OLI"。 */
    private String sensor;

    /** 最大云量百分比筛选，仅返回云量 <= 该值的影像。 */
    private BigDecimal maxCloudPercent;

    /** 空间边界框最小经度（WGS84），配合 maxLng/minLat/maxLat 构成矩形过滤。 */
    private BigDecimal minLng;

    /** 空间边界框最小纬度（WGS84）。 */
    private BigDecimal minLat;

    /** 空间边界框最大经度（WGS84）。 */
    private BigDecimal maxLng;

    /** 空间边界框最大纬度（WGS84）。 */
    private BigDecimal maxLat;

    /** 标记是否传入了空间边界框参数，由 Controller 层在组装查询条件时设置。 */
    private boolean hasBbox;
}
