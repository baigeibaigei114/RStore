package com.remotesensing.platform.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.config.properties.AuthProperties;
import com.remotesensing.platform.entity.SysUser;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.JwtTokenService;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * JWT 令牌服务实现类。
 *
 * <p>职责：使用 HS256 (HMAC-SHA256) 算法手动实现 JWT 令牌的生成与解析，
 * 不依赖第三方 JWT 库，减少外部依赖。
 *
 * <p>核心设计点：
 * <ul>
 *   <li>令牌结构：Base64Url(Header).Base64Url(Payload).Base64Url(Signature)；</li>
 *   <li>签名算法：HmacSHA256，密钥从 {@link AuthProperties#getJwtSecret()} 获取；</li>
 *   <li>Payload 携带 userId、username、role、iat（签发时间）、exp（过期时间）；</li>
 *   <li>解析时依次校验：格式完整性 -> 签名有效性 -> 是否过期，任一环节失败即拒绝；</li>
 *   <li>Token 过期时间由 {@code auth.token-expire-minutes} 配置控制。</li>
 * </ul>
 */
@Service
public class JwtTokenServiceImpl implements JwtTokenService {

    /** Jackson 反序列化 Map 的类型引用，避免每次解析时创建匿名类开销。 */
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AuthProperties authProperties;
    private final ObjectMapper objectMapper;

    public JwtTokenServiceImpl(AuthProperties authProperties, ObjectMapper objectMapper) {
        this.authProperties = authProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 为指定用户生成 JWT 令牌。
     *
     * <p>生成步骤：构造 Header -> 构造 Payload -> Base64Url 编码 -> HMAC-SHA256 签名。
     * 过期时间 = 当前时间 + 配置的分钟数。令牌中携带用户标识、角色、签发时间和过期时间。
     *
     * @param user 系统用户实体
     * @return 完整 JWT 令牌字符串（三段式，以 "." 分隔）
     */
    @Override
    public String generateToken(SysUser user) {
        // 计算签发时间（iat）和过期时间（exp）的 Unix 秒级时间戳。
        long now = Instant.now().getEpochSecond();
        long exp = now + authProperties.getTokenExpireMinutes() * 60;

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", String.valueOf(user.getId()));
        payload.put("username", user.getUsername());
        payload.put("role", user.getRole());
        payload.put("iat", now);
        payload.put("exp", exp);

        String headerPart = base64Url(toJsonBytes(header));
        String payloadPart = base64Url(toJsonBytes(payload));
        String signingInput = headerPart + "." + payloadPart;
        return signingInput + "." + sign(signingInput);
    }

    /**
     * 解析并校验 JWT 令牌。
     *
     * <p>校验流程：拆分三部分 -> 验证签名是否匹配 -> 解析 Payload JSON -> 检查是否过期。
     * 三个检查点任一不通过即抛出 {@link BusinessException}（状态码 UNAUTHORIZED）。
     *
     * @param token 完整 JWT 字符串
     * @return 解析后的用户信息（userId、username、role）
     * @throws BusinessException 格式错误 / 签名无效 / 令牌过期 / 解析失败时抛出
     */
    @Override
    public JwtUser parseToken(String token) {
        try {
            // 第一步：按 "." 拆分为三段，不足三段说明格式不合法。
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "token 格式不正确");
            }
            // 第二步：用同样的密钥重新计算签名，与传入的第三段比较，防止令牌被篡改。
            String signingInput = parts[0] + "." + parts[1];
            if (!sign(signingInput).equals(parts[2])) {
                throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "token 签名无效");
            }

            // 第三步：Base64Url 解码 Payload 并解析为 JSON，提取 exp 字段做过期校验。
            Map<String, Object> payload = objectMapper.readValue(Base64.getUrlDecoder().decode(parts[1]), MAP_TYPE);
            long exp = ((Number) payload.get("exp")).longValue();
            if (Instant.now().getEpochSecond() >= exp) {
                throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "token 已过期");
            }
            return new JwtUser(
                    String.valueOf(payload.get("userId")),
                    String.valueOf(payload.get("username")),
                    String.valueOf(payload.get("role"))
            );
        } catch (IllegalArgumentException | IOException exception) {
            // IllegalArgumentException 来自 Base64 解码失败，IOException 来自 JSON 解析失败。
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "token 解析失败");
        }
    }

    /**
     * 将 Map 序列化为 JSON 字节数组。序列化失败会直接抛出业务异常，避免返回无效令牌。
     */
    private byte[] toJsonBytes(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "token 序列化失败");
        }
    }

    /**
     * 使用 HmacSHA256 算法计算签名。
     * 密钥从配置获取，不在代码中硬编码，支持部署时按环境切换。
     */
    private String sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(authProperties.getJwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return base64Url(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "token 签名失败");
        }
    }

    /**
     * Base64Url 编码（无填充模式），生成的签名安全用于 URL 查询参数。
     */
    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

}
