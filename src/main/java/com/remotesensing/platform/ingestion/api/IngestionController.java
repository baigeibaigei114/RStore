package com.remotesensing.platform.ingestion.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.ingestion.workflow.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.remotesensing.platform.ingestion.api.IngestionProblem.require;
import static com.remotesensing.platform.ingestion.workflow.IngestionJson.*;

@RestController
@RequestMapping("/ingestion")
public class IngestionController {
    private final CurrentUserContext users;
    private final IngestionService service;
    private final IngestionStorage storage;
    private final IngestionUploadPump pump;
    private final IngestionJobService jobs;
    private final Semaphore downloads=new Semaphore(2);
    public IngestionController(CurrentUserContext users,IngestionService service,IngestionStorage storage,IngestionUploadPump pump,IngestionJobService jobs) {
        this.users=users;this.service=service;this.storage=storage;this.pump=pump;this.jobs=jobs;
    }
    private String owner() { return users.getAuthenticatedUserId(); }
    @PostMapping("/products") public ResponseEntity<?> create(HttpServletRequest request) {
        var created=service.create(owner(),read(request,"clientRequestId","name","fileName","sizeBytes"));
        return ResponseEntity.status(created.reused()?200:201).body(Result.success(created.data()));
    }
    @PutMapping("/products/{id}/content") public void upload(@PathVariable UUID id,HttpServletRequest request,HttpServletResponse response) {
        String owner=owner();
        require("application/zip".equalsIgnoreCase(request.getContentType()),400,"INVALID_REQUEST");
        var ticket=service.startUpload(owner,id,request.getContentLengthLong()); pump.start(request,response,ticket);
    }
    @GetMapping("/products") public Result<?> products(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize) {
        return Result.success(service.products(owner(),page,pageSize));
    }
    @GetMapping("/products/{id}") public Result<?> product(@PathVariable UUID id) { return Result.success(service.productView(owner(),id)); }
    @PostMapping("/products/{id}/retry") public ResponseEntity<?> retry(@PathVariable UUID id,HttpServletRequest request) {
        return ResponseEntity.accepted().body(Result.success(service.retry(owner(),id,read(request,"clientRequestId","expectedVersion"))));
    }
    @GetMapping("/products/{id}/preview") public ResponseEntity<byte[]> preview(@PathVariable UUID id) {
        var object=service.downloadable(owner(),id,"PREVIEW");storage.ensurePrivate();byte[] data=storage.preview(object);
        require(sha(data).equals(object.path("sha256").asText()),409,"INPUT_HASH_MISMATCH");
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("Cache-Control","private,no-store").body(data);
    }
    @GetMapping("/products/{id}/content") public void raw(@PathVariable UUID id,HttpServletRequest request,HttpServletResponse response) throws IOException {
        String owner=owner(); require(request.getHeader("Range")==null,416,"INVALID_REQUEST");
        var object=service.downloadable(owner,id,"RAW"); require(downloads.tryAcquire(),503,"CAPACITY_BUSY");
        try {
            storage.ensurePrivate();
            try(var source=storage.open(object)) {
                response.setContentType("application/zip");response.setHeader("Cache-Control","private,no-store");
                response.setHeader("Content-Disposition","attachment; filename=\"product.zip\"");response.setContentLengthLong(object.path("actual_bytes").asLong());
                byte[] buffer=new byte[65536];long count=0;int size;
                while((size=source.read(buffer))>=0) {count+=size;require(count<=object.path("actual_bytes").asLong(),409,"INPUT_HASH_MISMATCH");response.getOutputStream().write(buffer,0,size);}
                require(count==object.path("actual_bytes").asLong(),409,"INPUT_HASH_MISMATCH");
            }
        } finally {downloads.release();}
    }
    @PostMapping("/confirmations") public ResponseEntity<?> preflight(HttpServletRequest request) {
        var created=service.preflight(owner(),read(request,"clientRequestId","productId","monitoringRegionId","expectedRegionVersion","imageName"));
        return ResponseEntity.status(created.reused()?200:202).body(Result.success(created.data()));
    }
    @GetMapping("/confirmations/{id}") public Result<?> confirmation(@PathVariable UUID id) {return Result.success(service.confirmationView(owner(),id));}
    @PostMapping("/confirmations/{id}/confirm") public ResponseEntity<?> confirm(@PathVariable UUID id,HttpServletRequest request) {
        var created=jobs.confirm(owner(),id,read(request,"clientRequestId","confirmationHash"));
        return ResponseEntity.status(created.reused()?200:202).body(Result.success(created.data()));
    }
}
