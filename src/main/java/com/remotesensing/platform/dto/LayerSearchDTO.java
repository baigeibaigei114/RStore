package com.remotesensing.platform.dto;

import lombok.Data;

/**
 * 图层搜索查询 DTO。
 * 用于接收前端图层列表的多条件筛选请求，支持按任务类型、关联影像及关键词查询。
 */
@Data
public class LayerSearchDTO {

    /** 任务类型筛选，如 "NDVI"、"NDWI"、"CHANGE_DETECTION"，对应 rs_task 表 task_type 列。 */
    private String taskType;

    /** 关联影像主键 ID 筛选，对应 rs_image 表 id 列。 */
    private Long imageId;

    /** 搜索关键词，模糊匹配图层名称或关联影像名称。 */
    private String keyword;
}
