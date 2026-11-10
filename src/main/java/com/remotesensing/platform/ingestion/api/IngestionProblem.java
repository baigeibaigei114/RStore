package com.remotesensing.platform.ingestion.api;

/** Stable, non-provider error vocabulary. Never include paths, XML, SQL, or credentials. */
public class IngestionProblem extends RuntimeException {
    public final int status;
    public final String reason;
    public final boolean retryable;
    public final Long currentVersion;
    public IngestionProblem(int status, String reason) { this(status, reason, null); }
    public IngestionProblem(int status, String reason, Long version) {
        super(reason); this.status = status; this.reason = reason; this.currentVersion = version;
        this.retryable = status == 503 || reason.equals("LEASE_LOST") || reason.equals("DISPATCH_FAILED");
    }
    public static void require(boolean condition, int status, String reason) {
        if (!condition) throw new IngestionProblem(status, reason);
    }
}
