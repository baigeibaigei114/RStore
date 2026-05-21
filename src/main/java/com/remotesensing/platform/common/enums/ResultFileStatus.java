package com.remotesensing.platform.common.enums;

/**
 * 结果文件发布到 GeoServer 的状态枚举，对应 rs_result_file.status 字段。
 * <p>
 * 状态流转：PENDING_PUBLISH -> PUBLISHING -> PUBLISHED (终态)
 *                               -> PUBLISH_FAILED (终态)
 */
public enum ResultFileStatus {

    /** 等待发布到 GeoServer。 */
    PENDING_PUBLISH,

    /** 正在发布到 GeoServer 中。 */
    PUBLISHING,

    /** 已成功发布到 GeoServer，可通过 WMS/WCS 访问。 */
    PUBLISHED,

    /** 发布到 GeoServer 失败。 */
    PUBLISH_FAILED;

    /** 从数据库字符串转换为枚举，忽略大小写。 */
    public static ResultFileStatus fromDb(String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("结果文件发布状态不能为空");
        }
        try {
            return ResultFileStatus.valueOf(status.trim().toUpperCase());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("非法结果文件发布状态：" + status, exception);
        }
    }

    /** 返回数据库持久化时使用的字符串值（枚举名）。 */
    public String dbValue() {
        return name();
    }
}
