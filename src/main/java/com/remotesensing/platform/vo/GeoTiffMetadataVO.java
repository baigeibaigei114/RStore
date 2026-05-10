package com.remotesensing.platform.vo;

import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

@Data
public class GeoTiffMetadataVO {

    private Integer width;
    private Integer height;
    private Integer bandCount;
    private String crs;
    private Bounds bounds;
    private String boundsCrs;
    private Bounds originalBounds;
    private List<BigDecimal> transform;
    private Resolution resolution;
    private BigDecimal nodata;

    @Data
    public static class Bounds {

        private BigDecimal left;
        private BigDecimal bottom;
        private BigDecimal right;
        private BigDecimal top;
    }

    @Data
    public static class Resolution {

        private BigDecimal x;
        private BigDecimal y;
    }
}
