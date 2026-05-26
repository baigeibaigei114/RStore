package com.remotesensing.platform.vo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * GeoTIFF 文件元数据 VO。
 * 用于返回从 GeoTIFF 文件中解析出的栅格信息，包括尺寸、波段、坐标系、空间范围和分辨率。
 */
@Data
public class GeoTiffMetadataVO {

    /** 栅格像素宽度。 */
    private Integer width;

    /** 栅格像素高度。 */
    private Integer height;

    /** 波段数量。 */
    private Integer bandCount;

    /** 投影坐标系（CRS），如 "EPSG:4326"。 */
    private String crs;

    /** 空间范围（边界框），单位为 boundsCrs 指定的坐标系。 */
    private Bounds bounds;

    /** bounds 字段所使用的坐标系，通常为 "EPSG:4326"。 */
    private String boundsCrs;

    /** 原始 GeoTIFF 文件自身的空间范围（未经重投影处理）。 */
    private Bounds originalBounds;

    /** GeoTransform 仿射变换六参数 [原点X, X分辨率, X旋转, 原点Y, Y旋转, Y分辨率]。 */
    private List<BigDecimal> transform;

    /** 栅格分辨率（地图单位/像素），x 为水平方向，y 为垂直方向。 */
    private Resolution resolution;

    /** 分辨率单位，如 "meter"（米）或 "degree"（度）。 */
    private String resolutionUnit;

    /** 换算为米的分辨率值，便于统一比较不同 CRS 下的分辨率。 */
    private BigDecimal resolutionMeter;

    /** NoData 值，栅格中该值的像素将被视为无效数据。 */
    private BigDecimal nodata;

    private List<String> bandDescriptions;

    private List<String> colorInterpretations;

    private Map<String, Integer> bandMapping;

    private String bandMappingSource;

    private String bandMappingConfidence;

    private List<String> supportedTaskTypes;

    /**
     * 空间范围内部类，表示矩形边界框。
     * left 和 right 为水平方向最小/最大值，bottom 和 top 为垂直方向最小/最大值。
     */
    @Data
    public static class Bounds {

        /** 左边界（最小 X）。 */
        private BigDecimal left;

        /** 下边界（最小 Y）。 */
        private BigDecimal bottom;

        /** 右边界（最大 X）。 */
        private BigDecimal right;

        /** 上边界（最大 Y）。 */
        private BigDecimal top;
    }

    /**
     * 分辨率内部类，表示栅格在水平和垂直方向上的象元大小。
     */
    @Data
    public static class Resolution {

        /** 水平方向分辨率（X 轴）。 */
        private BigDecimal x;

        /** 垂直方向分辨率（Y 轴）。 */
        private BigDecimal y;
    }
}
