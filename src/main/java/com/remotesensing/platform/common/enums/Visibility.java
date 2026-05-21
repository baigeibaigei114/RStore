package com.remotesensing.platform.common.enums;

/**
 * 可见性枚举，用于控制影像和结果文件的访问权限。
 * <p>
 * - PRIVATE：仅所有者可见。
 * - PUBLIC：所有用户可见。
 * 对应 rs_image.visibility 和 rs_result_file.visibility 字段。
 */
public enum Visibility {

    /** 私有——仅资源所有者可查看和操作。 */
    PRIVATE,

    /** 公有——平台所有用户均可查看。 */
    PUBLIC;

    /** 从数据库字符串转换为枚举，忽略大小写。 */
    public static Visibility fromDb(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("visibility 不能为空");
        }
        return Visibility.valueOf(value.trim().toUpperCase());
    }

    /** 返回数据库持久化时使用的字符串值（枚举名）。 */
    public String dbValue() {
        return name();
    }
}
