package com.remotesensing.platform.agent;

import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AgentMapper {
    String COLUMNS = "id::text,owner_id,create_request_id::text,create_request_hash,status,version,"
            + "intent_json::text,candidate_json::text,draft_version,draft_json::text,draft_hash,draft_expires_at,"
            + "confirmed_version,confirmed_hash,task_id,operation_id::text,operation_kind,operation_deadline,operation_version,"
            + "model_call_count,tool_call_count,explanation_json::text,explanation_key,result_metadata_sha256,knowledge_index_id,"
            + "error_code,safe_error_message,retryable,created_at,updated_at";
    String STEP_COLUMNS = "id::text,execution_id::text,sequence,operation_id::text,client_request_id::text,request_hash,"
            + "kind,name,status,started_at,ended_at,duration_ms,safe_input_json::text,safe_output_json::text,error_code,"
            + "safe_error_message,provider,model_requested,model_returned,usage_json::text,attempt";

    @Select("SELECT clock_timestamp()") OffsetDateTime now();
    @Select("SELECT " + COLUMNS + " FROM ai_ndvi_execution WHERE id=CAST(#{id} AS uuid) AND owner_id=#{owner}")
    AgentExecution find(@Param("id") String id, @Param("owner") String owner);
    @Select("SELECT " + COLUMNS + " FROM ai_ndvi_execution WHERE id=CAST(#{id} AS uuid) AND owner_id=#{owner} FOR UPDATE")
    AgentExecution lock(@Param("id") String id, @Param("owner") String owner);
    @Select("SELECT " + COLUMNS + " FROM ai_ndvi_execution WHERE create_request_id=CAST(#{key} AS uuid) AND owner_id=#{owner}")
    AgentExecution byCreateKey(@Param("owner") String owner, @Param("key") String key);
    @Select("SELECT " + COLUMNS + " FROM ai_ndvi_execution WHERE owner_id=#{owner} ORDER BY updated_at DESC,id LIMIT #{limit} OFFSET #{offset}")
    List<AgentExecution> page(@Param("owner") String owner, @Param("offset") int offset, @Param("limit") int limit);
    @Select("SELECT count(*) FROM ai_ndvi_execution WHERE owner_id=#{owner}") long count(String owner);
    @Insert("""
        INSERT INTO ai_ndvi_execution(id,owner_id,create_request_id,create_request_hash,status,version,intent_json,candidate_json)
        VALUES(CAST(#{id} AS uuid),#{ownerId},CAST(#{createRequestId} AS uuid),#{createRequestHash},#{status},#{version},
            CAST(#{intentJson} AS jsonb),CAST(#{candidateJson} AS jsonb))
        """) int insert(AgentExecution execution);
    @Update("""
        UPDATE ai_ndvi_execution SET status=#{e.status},version=#{e.version},intent_json=CAST(#{e.intentJson} AS jsonb),
            candidate_json=CAST(#{e.candidateJson} AS jsonb),draft_version=#{e.draftVersion},draft_json=CAST(#{e.draftJson} AS jsonb),
            draft_hash=#{e.draftHash},draft_expires_at=#{e.draftExpiresAt},confirmed_version=#{e.confirmedVersion},
            confirmed_hash=#{e.confirmedHash},task_id=#{e.taskId},operation_id=CAST(#{e.operationId} AS uuid),
            operation_kind=#{e.operationKind},operation_deadline=#{e.operationDeadline},operation_version=#{e.operationVersion},
            model_call_count=#{e.modelCallCount},tool_call_count=#{e.toolCallCount},explanation_json=CAST(#{e.explanationJson} AS jsonb),
            explanation_key=#{e.explanationKey},result_metadata_sha256=#{e.resultMetadataSha256},knowledge_index_id=#{e.knowledgeIndexId},
            error_code=#{e.errorCode},safe_error_message=#{e.safeErrorMessage},retryable=#{e.retryable},updated_at=clock_timestamp()
        WHERE id=CAST(#{e.id} AS uuid) AND owner_id=#{e.ownerId} AND version=#{expected}
        """) int save(@Param("e") AgentExecution execution, @Param("expected") int expected);
    @Select("SELECT COALESCE(max(sequence),0)+1 FROM ai_ndvi_step WHERE execution_id=CAST(#{id} AS uuid)") int nextSequence(String id);
    @Select("SELECT " + STEP_COLUMNS + " FROM ai_ndvi_step WHERE execution_id=CAST(#{id} AS uuid) AND client_request_id=CAST(#{key} AS uuid)")
    AgentStep byRequest(@Param("id") String id, @Param("key") String key);
    @Insert("""
        INSERT INTO ai_ndvi_step(id,execution_id,sequence,operation_id,client_request_id,request_hash,kind,name,status,
           started_at,safe_input_json,attempt)
        VALUES(CAST(#{id} AS uuid),CAST(#{executionId} AS uuid),#{sequence},CAST(#{operationId} AS uuid),
           CAST(#{clientRequestId} AS uuid),#{requestHash},#{kind},#{name},#{status},#{startedAt},CAST(#{safeInputJson} AS jsonb),#{attempt})
        """) int insertStep(AgentStep step);
    @Update("""
        UPDATE ai_ndvi_step SET status=#{status},ended_at=#{endedAt},duration_ms=#{durationMs},
            safe_output_json=CAST(#{safeOutputJson} AS jsonb),error_code=#{errorCode},safe_error_message=#{safeErrorMessage},
            provider=#{provider},model_requested=#{modelRequested},model_returned=#{modelReturned},usage_json=CAST(#{usageJson} AS jsonb)
        WHERE id=CAST(#{id} AS uuid) AND execution_id=CAST(#{executionId} AS uuid)
          AND operation_id=CAST(#{operationId} AS uuid) AND status='RUNNING'
        """) int finishStep(AgentStep step);
    @Update("""
        UPDATE ai_ndvi_step SET duration_ms=#{durationMs},provider=#{provider},model_requested=#{modelRequested},
            model_returned=#{modelReturned},usage_json=CAST(#{usageJson} AS jsonb)
        WHERE id=CAST(#{id} AS uuid) AND execution_id=CAST(#{executionId} AS uuid)
          AND operation_id=CAST(#{operationId} AS uuid) AND status='INTERRUPTED'
        """) int accountInterruptedStep(AgentStep step);
    @Update("""
        UPDATE ai_ndvi_step SET status='INTERRUPTED',ended_at=clock_timestamp(),error_code='OPERATION_INTERRUPTED',
            safe_error_message='操作中断，迟到输出不再生效'
        WHERE execution_id=CAST(#{id} AS uuid) AND operation_id=CAST(#{operation} AS uuid) AND status='RUNNING'
        """) int interruptSteps(@Param("id") String id, @Param("operation") String operation);
    @Select("SELECT " + STEP_COLUMNS + " FROM ai_ndvi_step WHERE execution_id=CAST(#{id} AS uuid) AND sequence>#{after} ORDER BY sequence LIMIT 50")
    List<AgentStep> steps(@Param("id") String id, @Param("after") int after);
    @Select("""
        SELECT id::text,owner_id FROM ai_ndvi_execution WHERE operation_id IS NOT NULL
          AND operation_deadline<clock_timestamp() ORDER BY operation_deadline LIMIT 100
        """) List<AgentExecution> expiredOperations();
}
