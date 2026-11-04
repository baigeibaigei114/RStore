package com.remotesensing.platform.ingestion.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.config.properties.IngestionProperties;
import com.remotesensing.platform.ingestion.workflow.IngestionService;
import com.remotesensing.platform.ingestion.workflow.IngestionJson;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.HexFormat;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import static com.remotesensing.platform.ingestion.api.IngestionProblem.require;

/** Servlet non-blocking input: no background thread is left blocked waiting for upload bytes. */
@Component
public class IngestionUploadPump {
    private final IngestionService service;
    private final IngestionProperties config;
    private final ScheduledExecutorService timers=Executors.newScheduledThreadPool(2,r -> {Thread t=new Thread(r,"ingestion-upload-clock");t.setDaemon(true);return t;});
    private final ExecutorService storage=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),r -> {Thread t=new Thread(r,"ingestion-upload-store");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public IngestionUploadPump(IngestionService service,IngestionProperties config) {this.service=service;this.config=config;}
    public void start(HttpServletRequest request,HttpServletResponse response,IngestionService.Upload upload) {
        try { new Transfer(request,response,upload).start(); }
        catch(Exception error) {
            try { service.abortUpload(upload,false,"SERVICE_UNAVAILABLE"); } finally { service.uploadStopped(upload); }
            if(error instanceof IngestionProblem problem) throw problem;
            throw new IngestionProblem(503,"SERVICE_UNAVAILABLE");
        }
    }
    private final class Transfer implements ReadListener {
        private final HttpServletResponse response;
        private final IngestionService.Upload upload;
        private final AsyncContext async;
        private final ServletInputStream input;
        private final Path directory,file;
        private final OutputStream output;
        private final MessageDigest digest;
        private final AtomicBoolean responded=new AtomicBoolean(),storageStarted=new AtomicBoolean(),cleaned=new AtomicBoolean();
        private volatile boolean readingDone=false;
        private volatile long lastByte=System.nanoTime();
        private long count=0;
        private ScheduledFuture<?> timer;
        Transfer(HttpServletRequest request,HttpServletResponse response,IngestionService.Upload upload) throws Exception {
            this.response=response;this.upload=upload;
            Path configured=Path.of(config.getTempDirectory()).toAbsolutePath().normalize(); Files.createDirectories(configured);
            require(!Files.isSymbolicLink(configured),503,"SERVICE_UNAVAILABLE"); directory=configured.toRealPath();
            require(Files.getFileStore(directory).getUsableSpace()>=upload.expectedBytes()+config.getDiskSafetyBytes(),503,"RESOURCE_LIMIT");
            file=directory.resolve(upload.attemptId()+".part").normalize(); require(file.getParent().equals(directory),503,"SERVICE_UNAVAILABLE");
            output=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            digest=MessageDigest.getInstance("SHA-256"); input=request.getInputStream(); async=request.startAsync();
            async.setTimeout(Math.max(1,Duration.between(Instant.now(),upload.deadline()).toMillis()));
            async.addListener(new AsyncListener() {
                public void onTimeout(AsyncEvent event) { fail(new IngestionProblem(410,"UPLOAD_EXPIRED")); }
                public void onError(AsyncEvent event) { fail(new IngestionProblem(503,"SERVICE_UNAVAILABLE")); }
                public void onComplete(AsyncEvent event) {}
                public void onStartAsync(AsyncEvent event) {}
            });
        }
        void start() {
            timer=timers.scheduleAtFixedRate(() -> {
                if(responded.get()) return;
                try {
                    if(!upload.deadline().isAfter(Instant.now())) throw new IngestionProblem(410,"UPLOAD_EXPIRED");
                    if(!readingDone && System.nanoTime()-lastByte>TimeUnit.SECONDS.toNanos(config.getUploadIdleSeconds())) throw new IngestionProblem(410,"UPLOAD_EXPIRED");
                    service.uploadHeartbeat(upload);
                } catch(RuntimeException error) { fail(error instanceof IngestionProblem p?p:new IngestionProblem(503,"SERVICE_UNAVAILABLE")); }
            },10,10,TimeUnit.SECONDS);
            input.setReadListener(this);
        }
        @Override public void onDataAvailable() {
            byte[] buffer=new byte[65536];
            try {
                while(!responded.get() && input.isReady() && !input.isFinished()) {
                    int size=input.read(buffer); if(size<0) break; if(size==0) return;
                    count+=size; require(count<=upload.expectedBytes() && count<=config.getArchiveMaxBytes(),413,"UPLOAD_TOO_LARGE");
                    require(upload.deadline().isAfter(Instant.now()),410,"UPLOAD_EXPIRED");
                    output.write(buffer,0,size); digest.update(buffer,0,size); lastByte=System.nanoTime();
                }
            } catch(Exception error) { fail(error instanceof IngestionProblem p?p:new IngestionProblem(503,"SERVICE_UNAVAILABLE")); }
        }
        @Override public synchronized void onAllDataRead() {
            if(responded.get() || readingDone) return;
            try {
                output.close(); require(count==upload.expectedBytes(),400,"INVALID_REQUEST"); readingDone=true;
                String hash=HexFormat.of().formatHex(digest.digest()); storageStarted.set(true);
                storage.execute(() -> {
                    try {
                        ObjectNode result=service.finishUpload(upload,file,count,hash);
                        if(responded.compareAndSet(false,true)) respond(202,IngestionJson.JSON.createObjectNode().put("code",200).put("message","success").set("data",result));
                    } catch(RuntimeException error) { fail(error instanceof IngestionProblem p?p:new IngestionProblem(503,"SERVICE_UNAVAILABLE")); }
                    finally { cleanup(); }
                });
            } catch(Exception error) { storageStarted.set(false); fail(error instanceof IngestionProblem p?p:new IngestionProblem(503,"SERVICE_UNAVAILABLE")); }
        }
        @Override public void onError(Throwable error) { fail(new IngestionProblem(503,"SERVICE_UNAVAILABLE")); }
        private synchronized void fail(IngestionProblem problem) {
            if(!responded.compareAndSet(false,true)) return;
            try { input.close(); } catch(IOException ignored) {}
            try { output.close(); } catch(IOException ignored) {}
            try { service.abortUpload(upload,storageStarted.get(),problem.reason); }
            catch(RuntimeException ignored) { /* Unknown DB result remains recoverable; never delete an object here. */ }
            respond(problem.status,IngestionAdvice.response(problem));
            if(!storageStarted.get()) cleanup();
        }
        private void respond(int status,ObjectNode value) {
            if(timer!=null) timer.cancel(false);
            try {
                response.setStatus(status);response.setContentType("application/json;charset=UTF-8");response.setHeader("Cache-Control","private,no-store");
                response.getOutputStream().write(IngestionJson.JSON.writeValueAsBytes(value));
            } catch(IOException ignored) { /* Client may have disconnected; durable product status is authoritative. */ }
            finally { try {async.complete();} catch(IllegalStateException ignored) {} }
        }
        private void cleanup() {
            if(!cleaned.compareAndSet(false,true)) return;
            try { output.close(); } catch(IOException ignored) {}
            try { if(file.getParent().equals(directory) && !Files.isSymbolicLink(file)) Files.deleteIfExists(file); } catch(IOException ignored) {}
            // Reached only after input/file IO and any storage call have ended.
            try { service.uploadStopped(upload); } catch(RuntimeException ignored) { /* Failed receipt keeps the slot held/recovering. */ }
        }
    }
    @jakarta.annotation.PreDestroy public void close() {timers.shutdownNow();storage.shutdown();}
}
