package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.mapper.MessageOutboxMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import com.remotesensing.platform.service.RsTaskFailureService;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 有限收敛，不自动重新投递过期计算；每批持锁事务仅包含数据库操作。 */
@Service
@Profile("!test")
public class TaskRecoveryService {
    private final RsTaskMapper tasks;
    private final MessageOutboxMapper outbox;
    private final RsTaskFailureService failures;

    public TaskRecoveryService(RsTaskMapper tasks, MessageOutboxMapper outbox, RsTaskFailureService failures) {
        this.tasks = tasks;
        this.outbox = outbox;
        this.failures = failures;
    }

    @Scheduled(fixedDelayString = "${rabbitmq.remote-sensing-task.recovery-delay-ms:10000}")
    @Transactional
    public void recover() {
        for (var message : outbox.selectExhausted()) {
            outbox.exhaustSending(message.getId());
            // 失败服务SQL不会终结仍持有有效租约的RUNNING任务。
            failures.markFailedIfActive(message.getAggregateId(), "发布确认超时或发送预算耗尽，请新建任务重试",
                    Map.of("phase", "outbox-recovery", "outboxId", message.getId()));
        }
        for (Long id : tasks.selectExpiredTasks()) {
            failures.markFailedIfActive(id, "执行租约过期或尝试预算耗尽，请新建任务重试",
                    Map.of("phase", "execution-recovery"));
        }
    }
}
