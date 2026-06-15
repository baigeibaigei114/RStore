package com.remotesensing.platform.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.common.enums.AiPlanStatus;
import com.remotesensing.platform.dto.AiPlanContentDTO;
import com.remotesensing.platform.dto.AiPlanCreateDTO;
import com.remotesensing.platform.dto.AiPlanSearchDTO;
import com.remotesensing.platform.dto.LlmMessage;
import com.remotesensing.platform.entity.AiPlan;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.AiPlanMapper;
import com.remotesensing.platform.service.AiPlanService;
import com.remotesensing.platform.service.AiPlanValidator;
import com.remotesensing.platform.service.LlmClient;
import com.remotesensing.platform.vo.AiPlanListVO;
import com.remotesensing.platform.vo.AiPlanVO;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AI Plan 服务实现。
 */
@Service
public class AiPlanServiceImpl implements AiPlanService {

    private static final int DEFAULT_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int TITLE_MAX_LENGTH = 255;

    private static final String SYSTEM_PROMPT = """
            你是遥感影像智能分析规划助手。
            你的任务是把用户自然语言需求转换为结构化执行计划。
            你只能输出 JSON，不要 Markdown，不要解释，不要代码块。
            不要执行任何操作，不要生成 SQL，不要编造工具。
            step type 只能使用：RESOLVE_REGION, SEARCH_IMAGES, SELECT_IMAGE, SUBMIT_TASK, WAIT_TASK, GENERATE_REPORT, LIST_LAYERS, DOWNLOAD_RESULT。
            涉及提交任务、生成报告、下载结果、用户选择影像的步骤必须 requiresConfirmation=true。
            如果用户请求删除、公开、修改权限、执行 SQL、调用外部 URL，不要生成对应操作，请在步骤描述中说明暂不支持。
            输出字段固定为：goal, steps。
            每个 step 包含 order, type, description, params, requiresConfirmation。
            """;

    private final CurrentUserContext currentUserContext;
    private final AiPlanMapper aiPlanMapper;
    private final AiPlanValidator aiPlanValidator;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public AiPlanServiceImpl(CurrentUserContext currentUserContext,
                             AiPlanMapper aiPlanMapper,
                             AiPlanValidator aiPlanValidator,
                             LlmClient llmClient,
                             ObjectMapper objectMapper,
                             PlatformTransactionManager transactionManager) {
        this.currentUserContext = currentUserContext;
        this.aiPlanMapper = aiPlanMapper;
        this.aiPlanValidator = aiPlanValidator;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public AiPlanVO create(AiPlanCreateDTO requestDTO) {
        String currentUserId = currentUserContext.getCurrentUserId();
        // LLM 调用可能耗时较长，必须放在数据库事务外，避免长时间占用连接。
        AiPlanContentDTO planContent = generatePlan(requestDTO.getText());
        List<String> errors = aiPlanValidator.validate(planContent);
        return transactionTemplate.execute(status -> savePlan(currentUserId, requestDTO, planContent, errors));
    }

    private AiPlanVO savePlan(String currentUserId,
                              AiPlanCreateDTO requestDTO,
                              AiPlanContentDTO planContent,
                              List<String> errors) {
        AiPlan plan = new AiPlan();
        plan.setUserId(currentUserId);
        plan.setUserInput(requestDTO.getText());
        plan.setTitle(buildTitle(planContent));
        plan.setStatus(errors.isEmpty() ? AiPlanStatus.VALID.dbValue() : AiPlanStatus.INVALID.dbValue());
        plan.setPlanJson(writeJson(planContent));
        plan.setValidationErrors(writeJson(errors));
        aiPlanMapper.insert(plan);
        return toVO(plan, planContent, errors);
    }

    @Override
    public AiPlanVO getById(Long id) {
        AiPlan plan = loadOwnPlan(id);
        return toVO(plan);
    }

    @Override
    public PageResult<AiPlanListVO> page(AiPlanSearchDTO query, Integer pageNum, Integer pageSize) {
        String currentUserId = currentUserContext.getCurrentUserId();
        normalizeQuery(query);
        int safePageNum = pageNum == null || pageNum < 1 ? DEFAULT_PAGE_NUM : pageNum;
        int safePageSize = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        int offset = (safePageNum - 1) * safePageSize;
        List<AiPlanListVO> records = aiPlanMapper.pageByUser(currentUserId, query, safePageSize, offset)
                .stream()
                .map(this::toListVO)
                .toList();
        long total = aiPlanMapper.countByUser(currentUserId, query);
        return new PageResult<>(records, total, safePageNum, safePageSize);
    }

    @Override
    public AiPlanVO confirm(Long id) {
        AiPlan plan = loadOwnPlan(id);
        if (AiPlanStatus.fromDb(plan.getStatus()) != AiPlanStatus.VALID) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "只有 VALID 状态的 AI Plan 可以确认");
        }
        aiPlanMapper.updateStatusForUser(id, plan.getUserId(), AiPlanStatus.CONFIRMED.dbValue());
        return getById(id);
    }

    @Override
    public AiPlanVO cancel(Long id) {
        AiPlan plan = loadOwnPlan(id);
        if (AiPlanStatus.fromDb(plan.getStatus()) == AiPlanStatus.CONFIRMED) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "已确认的 AI Plan 暂不允许取消");
        }
        aiPlanMapper.updateStatusForUser(id, plan.getUserId(), AiPlanStatus.CANCELED.dbValue());
        return getById(id);
    }

    private AiPlanContentDTO generatePlan(String userInput) {
        String content = llmClient.chatJson(List.of(
                new LlmMessage("system", SYSTEM_PROMPT),
                new LlmMessage("user", buildUserPrompt(userInput))
        ));
        try {
            AiPlanContentDTO plan = objectMapper.readValue(content, AiPlanContentDTO.class);
            if (plan == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "AI Plan 生成失败，请简化需求");
            }
            return plan;
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException | JsonProcessingException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "AI Plan 生成失败，请简化需求");
        }
    }

    private String buildUserPrompt(String userInput) {
        return """
                用户需求：
                %s

                当前系统支持能力：
                - 查询行政区和行政区边界
                - 按时间、传感器、云量、关键字、bbox 或行政区检索影像
                - 由用户选择影像
                - 提交 NDVI、NDWI、CHANGE_DETECTION 任务
                - 等待任务完成
                - 基于成功任务生成 AI 分析报告
                - 查询已发布图层和下载处理结果
                本轮只生成计划，不执行任何步骤。
                """.formatted(userInput);
    }

    private AiPlan loadOwnPlan(Long id) {
        String currentUserId = currentUserContext.getCurrentUserId();
        AiPlan plan = aiPlanMapper.selectByIdForUser(id, currentUserId);
        if (plan == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "AI Plan 不存在或无权访问");
        }
        return plan;
    }

    private void normalizeQuery(AiPlanSearchDTO query) {
        if (query == null || query.getStatus() == null || query.getStatus().isBlank()) {
            return;
        }
        try {
            query.setStatus(AiPlanStatus.fromDb(query.getStatus()).dbValue());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "AI Plan 状态不合法");
        }
    }

    private String buildTitle(AiPlanContentDTO content) {
        String goal = content.getGoal();
        if (goal == null || goal.isBlank()) {
            return "AI 遥感分析计划";
        }
        String title = goal.trim();
        return title.length() <= TITLE_MAX_LENGTH ? title : title.substring(0, TITLE_MAX_LENGTH);
    }

    private AiPlanVO toVO(AiPlan plan) {
        return toVO(plan, readPlan(plan.getPlanJson()), readValidationErrors(plan.getValidationErrors()));
    }

    private AiPlanVO toVO(AiPlan plan, AiPlanContentDTO content, List<String> errors) {
        AiPlanVO vo = new AiPlanVO();
        vo.setId(plan.getId());
        vo.setStatus(plan.getStatus());
        vo.setTitle(plan.getTitle());
        vo.setUserInput(plan.getUserInput());
        vo.setPlan(content);
        vo.setValidationErrors(errors == null ? List.of() : errors);
        vo.setCreatedAt(plan.getCreatedAt());
        vo.setUpdatedAt(plan.getUpdatedAt());
        return vo;
    }

    private AiPlanListVO toListVO(AiPlan plan) {
        AiPlanListVO vo = new AiPlanListVO();
        vo.setId(plan.getId());
        vo.setStatus(plan.getStatus());
        vo.setTitle(plan.getTitle());
        vo.setUserInput(plan.getUserInput());
        vo.setCreatedAt(plan.getCreatedAt());
        vo.setUpdatedAt(plan.getUpdatedAt());
        return vo;
    }

    private AiPlanContentDTO readPlan(String json) {
        try {
            return objectMapper.readValue(json, AiPlanContentDTO.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "AI Plan JSON 格式异常");
        }
    }

    private List<String> readValidationErrors(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "AI Plan 校验结果格式异常");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "AI Plan JSON 序列化失败");
        }
    }
}
