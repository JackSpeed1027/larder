package com.larder.pipeline.control;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 任务中心接口：列表、详情、取消、重试。 */
@RestController
@RequestMapping("/api/jobs")
public class JobControlController {

    private final JobControlService service;

    public JobControlController(JobControlService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String state,
                                    @RequestParam(required = false) String itemId,
                                    @RequestParam(defaultValue = "20") int limit,
                                    @RequestParam(defaultValue = "0") int offset,
                                    Authentication auth) {
        return service.list(auth.getName(), state, itemId, limit, offset);
    }

    @GetMapping("/{jobId}")
    public Map<String, Object> detail(@PathVariable String jobId, Authentication auth) {
        return service.detail(auth.getName(), jobId);
    }

    @PostMapping("/{jobId}/cancel")
    public Map<String, Object> cancel(@PathVariable String jobId, Authentication auth) {
        return service.cancel(auth.getName(), jobId);
    }

    @PostMapping("/{jobId}/retry")
    public Map<String, Object> retry(@PathVariable String jobId, Authentication auth) {
        return service.retry(auth.getName(), jobId);
    }

    @ExceptionHandler(JobControlService.ControlException.class)
    public ResponseEntity<Map<String, String>> onError(JobControlService.ControlException ex) {
        return ResponseEntity.status(ex.status()).body(Map.of("code", ex.code(),
                "message", ex.getMessage() == null ? "" : ex.getMessage()));
    }
}
