package com.remotesensing.platform.common.enums;

/**
 * 缩略图生成状态枚举，对应 rs_image.thumbnail_status 字段。
 * <p>
 * 缩略图由异步线程池在影像上传后生成，不阻塞上传流程。
 * 状态流转：PENDING -> RUNNING -> SUCCESS (终态)
 *                              -> FAILED (终态，可重试)
 *                              -> SKIPPED（终端，缩略图已跳过）
 */
public enum ThumbnailStatus {

    /** 等待生成缩略图。 */
    PENDING,

    /** 缩略图正在生成中。 */
    RUNNING,

    /** 缩略图生成成功。 */
    SUCCESS,

    /** 缩略图生成失败。 */
    FAILED,

    /** 缩略图生成被跳过（如文件不支持的格式）。 */
    SKIPPED;

    /** 从数据库字符串转换为枚举，忽略大小写。 */
    public static ThumbnailStatus fromDb(String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("缩略图状态不能为空");
        }
        try {
            return ThumbnailStatus.valueOf(status.trim().toUpperCase());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("非法缩略图状态：" + status, exception);
        }
    }

    /** 返回数据库持久化时使用的字符串值（枚举名）。 */
    public String dbValue() {
        return name();
    }

    /** 当状态为 PENDING 时允许开始生成。 */
    public boolean canStart() {
        return this == PENDING;
    }

    /** 当状态为 PENDING 时允许重试。 */
    public boolean canRetry() {
        return this == PENDING;
    }
}
