package com.remotesensing.platform.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 认证相关配置属性，前缀为 "app.auth"。
 * <p>
 * 包含 JWT 密钥、Token 过期时间、开发模式用户头以及 Worker 令牌。
 * 默认值适用于本地开发环境，生产环境需在配置文件中覆盖。
 */
@Data
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    /** JWT 签名密钥，生产环境必须修改默认值。 */
    private String jwtSecret = "dev-secret-change-me";

    /** JWT Token 过期时间（分钟），默认 1440 分钟（24 小时）。 */
    private long tokenExpireMinutes = 1440;

    /** 是否启用开发模式用户头（X-User-Id），开发环境可跳过 JWT 校验。默认关闭。 */
    private boolean devUserHeaderEnabled = false;

    /** 开发模式下的默认用户 ID，仅当 devUserHeaderEnabled=true 且请求未携带 X-User-Id 时生效。 */
    private String defaultUserId = "dev-user";

    /** Worker 身份令牌，用于 Python Worker 回调认证。生产环境必须修改默认值。 */
    private String workerToken = "dev-worker-token-change-me";
}
