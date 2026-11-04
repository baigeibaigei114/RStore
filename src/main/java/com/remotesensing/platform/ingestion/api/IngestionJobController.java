package com.remotesensing.platform.ingestion.api;

import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.ingestion.workflow.IngestionJobService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.remotesensing.platform.ingestion.workflow.IngestionJson.read;

@RestController
@RequestMapping("/ingestion/jobs")
public class IngestionJobController {
    private final CurrentUserContext users;
    private final IngestionJobService service;
    public IngestionJobController(CurrentUserContext users,IngestionJobService service) {this.users=users;this.service=service;}
    @GetMapping public Result<?> list(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize) {
        return Result.success(service.list(users.getAuthenticatedUserId(),page,pageSize));
    }
    @GetMapping("/{id}") public Result<?> detail(@PathVariable UUID id) {return Result.success(service.view(users.getAuthenticatedUserId(),id));}
    @PostMapping("/{id}/retry") public ResponseEntity<?> retry(@PathVariable UUID id,HttpServletRequest request) {
        return ResponseEntity.accepted().body(Result.success(service.retry(users.getAuthenticatedUserId(),id,read(request,"clientRequestId","expectedVersion","productId"))));
    }
    @GetMapping("/{id}/publication") public Result<?> publication(@PathVariable UUID id) {return Result.success(service.publication(users.getAuthenticatedUserId(),id));}
}
