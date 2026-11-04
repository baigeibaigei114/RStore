package com.remotesensing.platform.ingestion.transport;

import com.remotesensing.platform.config.properties.IngestionProperties;
import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import com.remotesensing.platform.ingestion.runtime.IngestionValidationExecutor;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Durable LOCAL/VALIDATE only. PUBLISH stays pending for S4 and is never sent to Python. */
@Component
public class IngestionLocalDispatch {
    private static final Logger LOG=LoggerFactory.getLogger(IngestionLocalDispatch.class);
    private final IngestionStore db;
    private final IngestionProperties config;
    private final IngestionValidationExecutor execution;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r -> {Thread t=new Thread(r,"ingestion-local-validator");t.setDaemon(true);return t;});
    public IngestionLocalDispatch(IngestionStore db,IngestionProperties config,IngestionValidationExecutor execution) {this.db=db;this.config=config;this.execution=execution;}
    @Scheduled(fixedDelay=2000) public void dispatch() {
        if(!config.isEnabled() || !config.isStandardizationEnabled() || !execution.available() || busy.get())return;
        var rows=db.rows("SELECT id FROM ingest_outbox w WHERE dispatch_mode='LOCAL' AND kind='VALIDATE' AND status IN ('PENDING','SENDING')"
                + " AND next_send_at<=clock_timestamp() AND NOT EXISTS(SELECT 1 FROM ingest_attempt a WHERE a.work_id=w.id) ORDER BY created_at LIMIT 1");
        if(rows.isEmpty() || !busy.compareAndSet(false,true))return;
        try {worker.submit(() -> {try {execution.execute(IngestionStore.id(rows.get(0)));}catch(RuntimeException error) {LOG.warn("LOCAL验证工作未开始或待恢复");}finally {busy.set(false);}});}
        catch(RejectedExecutionException error) {busy.set(false);}
    }
    @jakarta.annotation.PreDestroy public void close() {worker.shutdownNow();}
}
