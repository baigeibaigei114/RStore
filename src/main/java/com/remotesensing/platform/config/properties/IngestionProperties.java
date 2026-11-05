package com.remotesensing.platform.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** SAFE-specific limits. They do not change the existing TIFF multipart/API defaults. */
@Data
@Component
@ConfigurationProperties(prefix = "ingestion")
public class IngestionProperties {
    private boolean enabled = false;
    private boolean standardizationEnabled = false;
    private boolean publicationEnabled = false;
    /** Operator confirms the private network after checking the deployment; ACL is also checked live. */
    private boolean mapBoundaryVerified = false;
    private long mapCopyQuota = 4L << 30;
    private int publishDeadlineSeconds = 120;
    private String bucketName;
    private String profileVersion = "safe-l2a-profile-v1";
    private String tempDirectory = "data/ingestion-upload";
    private long archiveMaxBytes = 2L << 30;
    private long ownerRawQuota = 4L << 30;
    private long globalRawQuota = 16L << 30;
    private long previewMaxBytes = 8L << 20;
    private long diskSafetyBytes = 1L << 30;
    private int uploadReservationSeconds = 1800;
    private int uploadDeadlineSeconds = 1200;
    private int uploadIdleSeconds = 60;
    private int rawRetentionDays = 7;
    private int confirmationSeconds = 600;
    private int leaseSeconds = 120;
    private int queueDeadlineSeconds = 1800;
    private int inspectDeadlineSeconds = 300;
    private int preflightDeadlineSeconds = 30;
    private int ndviDeadlineSeconds = 900;
    private int standardizeDeadlineSeconds = 900;
    private int validateDeadlineSeconds = 120;
    private long tiffMaxBytes = 256L << 20;
    private long manifestMaxBytes = 65536;
    private long ownerAssetQuota = 2L << 30;
    private long globalAssetQuota = 4L << 30;
    private int ownerActiveJobs = 2;
    private int maxWaitingWorks = 10;
}
