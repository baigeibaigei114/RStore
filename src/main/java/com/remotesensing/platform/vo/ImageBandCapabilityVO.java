package com.remotesensing.platform.vo;

import java.util.List;
import java.util.Map;

/**
 * 影像波段能力视图对象。
 *
 * @param bandMapping 波段角色到实际波段编号的映射，如 red -> 1、nir -> 4
 * @param source 映射来源，如 GEOTIFF_BAND_DESCRIPTION、USER_CONFIRMED
 * @param confidence 映射置信度，如 HIGH、MEDIUM、LOW
 * @param supportedTaskTypes 当前映射可支撑的任务类型，如 NDVI、NDWI
 */
public record ImageBandCapabilityVO(
        Map<String, Integer> bandMapping,
        String source,
        String confidence,
        List<String> supportedTaskTypes
) {
}
