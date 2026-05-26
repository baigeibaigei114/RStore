package com.remotesensing.platform.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 影像波段映射手动确认请求。
 *
 * <p>用于系统无法自动识别波段角色时，由用户按影像真实波段顺序填写 red/green/blue/nir 编号。</p>
 */
@Data
public class RsImageBandMappingUpdateDTO {

    /** 用户确认的红光波段编号。 */
    @Min(value = 1, message = "redBand 必须大于 0")
    private Integer redBand;

    /** 用户确认的绿光波段编号。 */
    @Min(value = 1, message = "greenBand 必须大于 0")
    private Integer greenBand;

    /** 用户确认的蓝光波段编号。 */
    @Min(value = 1, message = "blueBand 必须大于 0")
    private Integer blueBand;

    /** 用户确认的近红外波段编号，NDVI/NDWI 计算依赖该字段。 */
    @Min(value = 1, message = "nirBand 必须大于 0")
    private Integer nirBand;
}
