package com.remotesensing.platform.mapper;

import com.remotesensing.platform.entity.MessageOutbox;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MessageOutboxMapper {

    int insert(MessageOutbox outbox);

    MessageOutbox selectById(@Param("id") Long id);

    MessageOutbox selectByTaskId(@Param("taskId") Long taskId);

    List<MessageOutbox> selectDueMessages(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    int markPublishAttempt(@Param("id") Long id,
                           @Param("now") OffsetDateTime now,
                           @Param("nextRetryAt") OffsetDateTime nextRetryAt);

    int markSentIfSending(@Param("id") Long id);

    int markSentIfNotFailed(@Param("id") Long id);

    int markFailedIfSending(@Param("id") Long id,
                            @Param("errorMessage") String errorMessage,
                            @Param("nextRetryAt") OffsetDateTime nextRetryAt);
}
