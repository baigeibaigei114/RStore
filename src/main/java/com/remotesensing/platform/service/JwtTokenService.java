package com.remotesensing.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.config.AuthProperties;
import com.remotesensing.platform.entity.SysUser;
import com.remotesensing.platform.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AuthProperties authProperties;
    private final ObjectMapper objectMapper;

    public JwtTokenService(AuthProperties authProperties, ObjectMapper objectMapper) {
        this.authProperties = authProperties;
        this.objectMapper = objectMapper;
    }

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
        payload.put("iat", now);
        payload.put("exp", exp);

        String headerPart = base64Url(toJsonBytes(header));
        String payloadPart = base64Url(toJsonBytes(payload));
        String signingInput = headerPart + "." + payloadPart;
        return signingInput + "." + sign(signingInput);
    }

    public JwtUser parseToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "token 格式不正确");
            }
            String signingInput = parts[0] + "." + parts[1];
            if (!sign(signingInput).equals(parts[2])) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "token 签名无效");
            }

            Map<String, Object> payload = objectMapper.readValue(Base64.getUrlDecoder().decode(parts[1]), MAP_TYPE);
            long exp = ((Number) payload.get("exp")).longValue();
            if (Instant.now().getEpochSecond() >= exp) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "token 已过期");
            }
            return new JwtUser(
                    String.valueOf(payload.get("userId")),
                    String.valueOf(payload.get("username")),
                    String.valueOf(payload.get("role"))
            );
        } catch (IllegalArgumentException | IOException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "token 解析失败");
        }
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

    public record JwtUser(String userId, String username, String role) {
    }
}
