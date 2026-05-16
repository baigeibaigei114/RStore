package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.dto.LoginRequestDTO;
import com.remotesensing.platform.entity.SysUser;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.SysUserMapper;
import com.remotesensing.platform.service.AuthService;
import com.remotesensing.platform.service.JwtTokenService;
import com.remotesensing.platform.vo.AuthLoginVO;
import com.remotesensing.platform.vo.CurrentUserVO;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthServiceImpl implements AuthService {

    private static final String USER_STATUS_ACTIVE = "ACTIVE";

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final CurrentUserContext currentUserContext;

    public AuthServiceImpl(SysUserMapper sysUserMapper,
                           PasswordEncoder passwordEncoder,
                           JwtTokenService jwtTokenService,
                           CurrentUserContext currentUserContext) {
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.currentUserContext = currentUserContext;
    }

    @Override
    public AuthLoginVO login(LoginRequestDTO requestDTO) {
        SysUser user = sysUserMapper.selectByUsername(requestDTO.getUsername());
        if (user == null || !passwordEncoder.matches(requestDTO.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "用户名或密码错误");
        }
        if (!USER_STATUS_ACTIVE.equals(user.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "用户已禁用");
        }

        AuthLoginVO vo = toLoginVO(user);
        vo.setAccessToken(jwtTokenService.generateToken(user));
        vo.setTokenType("Bearer");
        return vo;
    }

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
