package com.remotesensing.platform.ingestion.api;

import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.ingestion.workflow.IngestionPublicationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.remotesensing.platform.ingestion.workflow.IngestionJson.read;

@RestController
@RequestMapping("/ingestion/jobs")
public class IngestionPublicationController {
    private final CurrentUserContext users;private final IngestionPublicationService publications;
    public IngestionPublicationController(CurrentUserContext users,IngestionPublicationService publications){this.users=users;this.publications=publications;}
    @PostMapping("/{id}/publication/retry") public ResponseEntity<?> retry(@PathVariable UUID id,HttpServletRequest request){
        return ResponseEntity.accepted().body(Result.success(publications.retry(users.getAuthenticatedUserId(),id,read(request,"clientRequestId","expectedVersion"))));
    }
}
