package com.remotesensing.platform.entity;

import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class SysUser {

    private Long id;
    private String username;
    private String passwordHash;
    private String displayName;
    private String role;
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
