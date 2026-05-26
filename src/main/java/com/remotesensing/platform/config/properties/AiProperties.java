package com.remotesensing.platform.config.properties;

import java.math.BigDecimal;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 模型调用配置，前缀为 app.ai。
 */
@Data
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    /** 是否启用 AI 能力；关闭后调用 AI 接口会返回明确业务异常。 */
    private boolean enabled = true;

    /** 模型提供方类型，第一版仅支持 openai-compatible。 */
    private String provider = "openai-compatible";

    /** OpenAI-compatible Chat Completions 基础地址。 */
    private String baseUrl = "https://api.deepseek.com/v1";

    /** 模型 API Key，通过环境变量注入，不能写死在代码或配置文件中。 */
    private String apiKey = "";

    /** 默认模型名称，可通过配置切换到其他兼容模型。 */
    private String model = "deepseek-v4-flash";

    /** HTTP 调用超时时间。 */
    private int timeoutSeconds = 30;

    /** 模型采样温度，解析类任务默认保持较低随机性。 */
    private BigDecimal temperature = new BigDecimal("0.1");

    /** 模型最大输出 token 数，避免异常长响应拖垮接口和数据库。 */
    private int maxTokens = 1200;
}
