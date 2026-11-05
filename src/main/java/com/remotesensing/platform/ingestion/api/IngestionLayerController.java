package com.remotesensing.platform.ingestion.api;

import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.ingestion.workflow.IngestionLayerService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/ingestion/layers")
public class IngestionLayerController {
    private final CurrentUserContext users;private final IngestionLayerService layers;
    public IngestionLayerController(CurrentUserContext users,IngestionLayerService layers){this.users=users;this.layers=layers;}
    @GetMapping public Result<?> list(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize){return Result.success(layers.list(users.getAuthenticatedUserId(),page,pageSize));}
    @GetMapping("/{id}/wms") public ResponseEntity<byte[]> wms(@PathVariable UUID id,@RequestParam MultiValueMap<String,String> input,HttpServletRequest request){
        IngestionProblem.require("GET".equals(request.getMethod()),405,"INVALID_REQUEST");
        return ResponseEntity.ok().header("Cache-Control","private,no-store").header("X-Content-Type-Options","nosniff")
                .header("Content-Type","image/png").body(layers.wms(users.getAuthenticatedUserId(),id,input));
    }
    @GetMapping("/{id}/wcs") public ResponseEntity<StreamingResponseBody> wcs(@PathVariable UUID id,@RequestParam MultiValueMap<String,String> input,HttpServletRequest request){
        IngestionProblem.require("GET".equals(request.getMethod()),405,"INVALID_REQUEST");
        var coverage=layers.wcs(users.getAuthenticatedUserId(),id,input);
        StreamingResponseBody body=out -> {try(coverage){java.nio.file.Files.copy(coverage.path(),out);}};
        return ResponseEntity.ok().header("Cache-Control","private,no-store").header("X-Content-Type-Options","nosniff")
                .header("Content-Type","image/tiff").header("Content-Disposition","attachment; filename=standardized.tif").contentLength(coverage.bytes()).body(body);
    }
}
