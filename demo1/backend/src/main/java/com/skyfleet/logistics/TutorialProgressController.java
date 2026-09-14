package com.skyfleet.logistics;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/demo/tutorials")
public class TutorialProgressController {
    private final TutorialProgressService tutorials;
    private final VisitorIdentity visitors;

    public TutorialProgressController(TutorialProgressService tutorials, VisitorIdentity visitors) {
        this.tutorials = tutorials; this.visitors = visitors;
    }

    @GetMapping
    public Map<String, Object> view(HttpServletRequest request, HttpServletResponse response) {
        return tutorials.view(visitors.resolve(request, response).visitorHash());
    }

    @PutMapping("/progress")
    public Map<String, Object> sync(@RequestBody ProgressRequest body, HttpServletRequest request, HttpServletResponse response) {
        return tutorials.syncClientProgress(visitors.resolve(request, response).visitorHash(),
                body.tutorialId(), body.tutorialVersion(), body.status());
    }

    public record ProgressRequest(String tutorialId, String tutorialVersion, String status) {}
}
