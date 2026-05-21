package com.remotesensing.platform.mapper;

import com.remotesensing.platform.entity.RsTaskLog;
import com.remotesensing.platform.vo.RsTaskLogVO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 任务日志（rs_task_log）Mapper 接口。
 * 提供任务执行日志的写入和查询操作，用于跟踪任务处理过程中的关键事件。
 */
@Mapper
public interface RsTaskLogMapper {

    /**
     * 插入一条任务日志记录。
     *
     * @param taskLog 任务日志实体，插入后 MyBatis 回填 id
     * @return 受影响行数
     */
    int insert(RsTaskLog taskLog);

    /**
     * 根据任务 ID 查询所有日志，按创建时间升序排列。
     *
     * @param taskId 任务主键
     * @return 日志 VO 集合，按时间从早到晚排序
     */
    List<RsTaskLogVO> selectByTaskIdOrderByCreatedAt(@Param("taskId") Long taskId);
}