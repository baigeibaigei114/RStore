package com.remotesensing.platform.service;

import com.remotesensing.platform.dto.LoginRequestDTO;
import com.remotesensing.platform.vo.AuthLoginVO;
import com.remotesensing.platform.vo.CurrentUserVO;

public interface AuthService {

    AuthLoginVO login(LoginRequestDTO requestDTO);

    CurrentUserVO me();
}
