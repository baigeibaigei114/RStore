package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.dto.LoginRequestDTO;
import com.remotesensing.platform.entity.SysUser;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.SysUserMapper;
import com.remotesensing.platform.service.AuthService;
import com.remotesensing.platform.service.JwtTokenService;
import com.remotesensing.platform.service.TokenBlacklistService;
import com.remotesensing.platform.vo.AuthLoginVO;
import com.remotesensing.platform.vo.CurrentUserVO;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 用户认证服务实现类。
 *
 * <p>职责：处理用户登录认证、当前登录用户信息查询等核心身份认证逻辑。
 * 核心设计点：
 * <ul>
 *   <li>密码校验委托给 Spring Security {@link PasswordEncoder}，支持密码哈希策略的灵活切换；</li>
 *   <li>登录成功后生成 JWT 令牌，由 {@link JwtTokenService} 负责令牌的生成与解析；</li>
 *   <li>通过 {@link CurrentUserContext} 获取当前请求上下文中的用户标识，与服务层解耦；</li>
 *   <li>用户状态检查在登录时执行，已禁用用户拒绝颁发令牌。</li>
 * </ul>
 * </p>
 */
@Service
public class AuthServiceImpl implements AuthService {

    /** 用户状态为"激活"的数据库值，只有此状态允许登录。 */
    private static final String USER_STATUS_ACTIVE = "ACTIVE";

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final CurrentUserContext currentUserContext;
    private final TokenBlacklistService tokenBlacklistService;

    public AuthServiceImpl(SysUserMapper sysUserMapper,
                           PasswordEncoder passwordEncoder,
                           JwtTokenService jwtTokenService,
                           CurrentUserContext currentUserContext,
                           TokenBlacklistService tokenBlacklistService) {
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.currentUserContext = currentUserContext;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    /**
     * 用户登录认证。
     *
     * <p>校验流程：用户名是否存在 -> 密码是否匹配 -> 用户是否被禁用。
     * 认证通过后生成 JWT 访问令牌并返回给调用方。
     * 事务属性：无事务要求，登录仅为查询操作。
     *
     * @param requestDTO 登录请求参数（用户名 + 密码）
     * @return 登录成功后的用户信息及令牌
     * @throws BusinessException 用户名或密码错误 / 用户已被禁用时抛出
     */
    @Override
    public AuthLoginVO login(LoginRequestDTO requestDTO) {
        // 第一步：按用户名查询用户记录。不存在则直接返回错误，避免枚举注册用户。
        SysUser user = sysUserMapper.selectByUsername(requestDTO.getUsername());
        if (user == null || !passwordEncoder.matches(requestDTO.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "用户名或密码错误");
        }
        // 第二步：校验用户状态。已禁用（非 ACTIVE）的用户即使密码正确也不允许登录。
        if (!USER_STATUS_ACTIVE.equals(user.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "用户已禁用");
        }

        // 第三步：构造登录响应，生成 JWT 令牌。令牌中包含用户标识、角色和过期时间。
        AuthLoginVO vo = toLoginVO(user);
        vo.setAccessToken(jwtTokenService.generateToken(user));
        vo.setTokenType("Bearer");
        return vo;
    }

    /**
     * 获取当前登录用户信息。
     *
     * <p>从 {@link CurrentUserContext} 中解析当前用户 ID。
     * 若用户 ID 非数字格式（如匿名用户），则返回包含默认角色的只读视图；
     * 否则从数据库查询完整用户信息返回。
     * 事务属性：只读事务。
     *
     * @return 当前用户信息视图对象
     * @throws BusinessException 用户不存在时抛出
     */
    @Override
    public CurrentUserVO me() {
        String currentUserId = currentUserContext.getCurrentUserId();
        Long userId = parseLongOrNull(currentUserId);
        if (userId == null) {
            CurrentUserVO vo = new CurrentUserVO();
            vo.setUserId(currentUserId);
            vo.setUsername(currentUserId);
            vo.setDisplayName(currentUserId);
            vo.setRole("USER");
            return vo;
        }

        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "当前用户不存在");
        }
        return toCurrentUserVO(user);
    }

    @Override
    public void logout(String token) {
        tokenBlacklistService.blacklist(token);
    }

    private AuthLoginVO toLoginVO(SysUser user) {
        AuthLoginVO vo = new AuthLoginVO();
        vo.setUserId(String.valueOf(user.getId()));
        vo.setUsername(user.getUsername());
        vo.setDisplayName(user.getDisplayName());
        vo.setRole(user.getRole());
        return vo;
    }

    private CurrentUserVO toCurrentUserVO(SysUser user) {
        CurrentUserVO vo = new CurrentUserVO();
        vo.setUserId(String.valueOf(user.getId()));
        vo.setUsername(user.getUsername());
        vo.setDisplayName(user.getDisplayName());
        vo.setRole(user.getRole());
        return vo;
    }

    private Long parseLongOrNull(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
