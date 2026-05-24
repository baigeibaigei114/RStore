package com.remotesensing.platform.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.config.properties.AuthProperties;
import com.remotesensing.platform.entity.SysUser;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.JwtTokenService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * JWT 令牌服务实现。
 *
 * <p>项目当前只需要轻量登录鉴权，因此手动实现 HS256 JWT，避免引入完整 Spring Security 过滤链。
 */
@Service
public class JwtTokenServiceImpl implements JwtTokenService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AuthProperties authProperties;
    private final ObjectMapper objectMapper;

    public JwtTokenServiceImpl(AuthProperties authProperties, ObjectMapper objectMapper) {
        this.authProperties = authProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String generateToken(SysUser user) {
        long now = Instant.now().getEpochSecond();
        long exp = now + authProperties.getTokenExpireMinutes() * 60;

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", String.valueOf(user.getId()));
        payload.put("username", user.getUsername());
        payload.put("role", user.getRole());
        payload.put("jti", UUID.randomUUID().toString());
        payload.put("iat", now);
        payload.put("exp", exp);

        String headerPart = base64Url(toJsonBytes(header));
        String payloadPart = base64Url(toJsonBytes(payload));
        String signingInput = headerPart + "." + payloadPart;
        return signingInput + "." + sign(signingInput);
    }

    @Override
    public JwtUser parseToken(String token) {
        return parseTokenPayload(token, true);
    }

    @Override
    public JwtUser parseTokenIgnoringExpiration(String token) {
        return parseTokenPayload(token, false);
    }

    private JwtUser parseTokenPayload(String token, boolean validateExpiration) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "token 格式不正确");
            }

            String signingInput = parts[0] + "." + parts[1];
            if (!sign(signingInput).equals(parts[2])) {
                throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "token 签名无效");
            }

            Map<String, Object> payload = objectMapper.readValue(Base64.getUrlDecoder().decode(parts[1]), MAP_TYPE);
            long exp = numberClaim(payload, "exp");
            if (validateExpiration && Instant.now().getEpochSecond() >= exp) {
                throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "token 已过期");
            }
            return new JwtUser(
                    stringClaim(payload, "userId"),
                    stringClaim(payload, "username"),
                    stringClaim(payload, "role"),
                    stringClaim(payload, "jti"),
                    exp
            );
        } catch (IllegalArgumentException | IOException exception) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "token 解析失败");
        }
    }

    private String stringClaim(Map<String, Object> payload, String claimName) {
        Object value = payload.get(claimName);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "token 缺少 " + claimName);
        }
        return String.valueOf(value);
    }

    private long numberClaim(Map<String, Object> payload, String claimName) {
        Object value = payload.get(claimName);
        if (!(value instanceof Number number)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "token 缺少 " + claimName);
        }
        return number.longValue();
    }

    private byte[] toJsonBytes(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "token 序列化失败");
        }
    }

    private String sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(authProperties.getJwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return base64Url(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "token 签名失败");
        }
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
