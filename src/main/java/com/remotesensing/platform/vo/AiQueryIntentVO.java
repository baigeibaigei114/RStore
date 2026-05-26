package com.remotesensing.platform.vo;

import com.remotesensing.platform.dto.RemoteSensingTaskMessage.TaskType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Data;

/**
 * AI 解析出的结构化影像检索意图。
 */
@Data
public class AiQueryIntentVO {

    /** 行政区或自然地理区域名称，本轮不强行解析为 regionId。 */
    private String regionName;

    /** 采集开始时间。 */
    private OffsetDateTime startTime;

    /** 采集结束时间。 */
    private OffsetDateTime endTime;

    /** 传感器名称，如 Sentinel-2。 */
    private String sensor;

    /** 最大云量百分比，范围 0-100。 */
    private BigDecimal maxCloudPercent;

    /** 用户意图中包含的任务类型。 */
    private List<TaskType> taskTypes;
}
