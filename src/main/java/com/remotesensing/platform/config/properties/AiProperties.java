package com.remotesensing.platform.config.properties;

import java.math.BigDecimal;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 模型调用配置，前缀为 {@code app.ai}。
 *
 * <p>所有 AI 调用参数集中管理，支持通过 application.yml 或环境变量覆盖。
 * API Key 通过环境变量注入，避免硬编码在配置文件中。{@code enabled} 总开关
 * 可一键关闭所有 AI 接口。</p>
 *
 * <p>协议选择：当前仅支持 {@code openai-compatible}，对接 DeepSeek、OpenAI、
 * Ollama 等兼容 {@code /v1/chat/completions} 协议的服务。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    /** 是否启用 AI 能力；关闭后调用 AI 接口会返回明确业务异常。 */
    private boolean enabled = true;

    /**
     * 模型提供方类型，当前仅支持 {@code openai-compatible}。
     * 保留字段，用于后续扩展其他协议（如 Anthropic Messages API）。
     */
    private String provider = "openai-compatible";

    /** OpenAI-compatible Chat Completions 基础地址。 */
    private String baseUrl = "https://api.deepseek.com/v1";

    /** 模型 API Key。建议通过 {@code APP_AI_API_KEY} 环境变量注入，避免明文写在配置文件中。 */
    private String apiKey = "";

    /** 模型名称，可通过配置在 DeepSeek、OpenAI 或其他兼容模型间切换。 */
    private String model = "deepseek-v4-flash";

    /** HTTP 调用超时（秒），同时控制连接超时和读取超时。 */
    private int timeoutSeconds = 30;

    /**
     * 模型采样温度，范围 [0, 2]。
     * 0 表示确定性最强（每次输出一致），2 表示随机性最强。
     * 解析类任务（提取结构化参数/报告摘要）默认低温度以保证输出稳定。
     */
    private BigDecimal temperature = new BigDecimal("0.1");

    /** 模型最大输出 token 数，上限保护 —— 避免模型异常时返回超长文本拖垮接口响应和数据库存储。 */
    private int maxTokens = 1200;
}
