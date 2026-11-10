package com.remotesensing.platform.resultanalysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.remotesensing.platform.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/agent/task-results/{taskId}/answers")
public class TaskResultAnswerController {
    private final TaskResultAnswerService service;

    TaskResultAnswerController(TaskResultAnswerService service) {
        this.service = service;
    }

    @GetMapping
    public Result<JsonNode> get(@PathVariable long taskId,
                                @RequestParam(required = false) String version,
                                @RequestParam(required = false) Integer beforeTurn) {
        return Result.success(service.get(taskId, version, beforeTurn));
    }

    @PostMapping
    public Result<JsonNode> ask(@PathVariable long taskId, @RequestBody JsonNode body) {
        return Result.success(service.ask(taskId, body));
    }
}
