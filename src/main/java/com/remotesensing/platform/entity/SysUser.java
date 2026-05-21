package com.remotesensing.platform.entity;

import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 系统用户实体，对应 sys_user 表。
 * <p>
 * 存储平台用户的登录凭证、显示名称和角色信息。
 * 密码使用 BCrypt 加密后存储。
 */
@Data
public class SysUser {

    /** 主键 ID。 */
    private Long id;

    /** 用户名（登录用）。 */
    private String username;

    /** 密码 BCrypt 散列值。 */
    private String passwordHash;

    /** 用户显示名称。 */
    private String displayName;

    /** 用户角色，如 ADMIN / USER。 */
    private String role;

    /** 用户状态：ACTIVE / DISABLED。 */
    private String status;

    /** 创建时间。 */
    private OffsetDateTime createdAt;

    /** 最后更新时间。 */
    private OffsetDateTime updatedAt;
}
