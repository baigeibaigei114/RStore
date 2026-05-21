package com.remotesensing.platform.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 任务提交响应 VO。
 * 任务提交成功后返回的唯一标识，前端可用该 ID 轮询任务状态或跳转到任务详情页。
 */
@Data
@AllArgsConstructor
public class RsTaskSubmitVO {

    /** 新创建的任务主键 ID，对应 rs_task.id。 */
    private Long taskId;
}
