package com.zhixun.demo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/demo/sessions")
public class DemoController {
    private final DemoSessionService sessions;
    private final VisitorIdentity visitors;

    public DemoController(DemoSessionService sessions, VisitorIdentity visitors) {
        this.sessions = sessions;
        this.visitors = visitors;
    }

    @PostMapping
    public Map<String, Object> create(HttpServletRequest request, HttpServletResponse response) {
        VisitorIdentity.Identity visitor = visitors.resolve(request, response);
        return sessions.create(visitor.visitorHash(), source(request));
    }

    @GetMapping("/current")
    public Map<String, Object> current(HttpServletRequest request, HttpServletResponse response) {
        VisitorIdentity.Identity visitor = visitors.resolve(request, response);
        return sessions.current(visitor.visitorHash());
    }

    @GetMapping("/{id}/mission")
    public Map<String, Object> mission(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) {
        VisitorIdentity.Identity visitor = visitors.resolve(request, response);
        return sessions.mission(id, visitor.visitorHash());
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) {
        VisitorIdentity.Identity visitor = visitors.resolve(request, response);
        return sessions.events(id, visitor.visitorHash());
    }

    @PatchMapping("/{id}/speed")
    public Map<String, Object> speed(@PathVariable String id, @RequestBody SpeedRequest body,
                                     HttpServletRequest request, HttpServletResponse response) {
        VisitorIdentity.Identity visitor = visitors.resolve(request, response);
        return sessions.speed(id, visitor.visitorHash(), body.timeScale());
    }

    @PostMapping("/{id}/restart")
    public Map<String, Object> restart(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) {
        VisitorIdentity.Identity visitor = visitors.resolve(request, response);
        return sessions.restart(id, visitor.visitorHash(), source(request));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> stop(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) {
        VisitorIdentity.Identity visitor = visitors.resolve(request, response);
        return sessions.stop(id, visitor.visitorHash());
    }

    private static String source(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",", 2)[0].trim();
        return request.getRemoteAddr();
    }

    public record SpeedRequest(double timeScale) {}
}
