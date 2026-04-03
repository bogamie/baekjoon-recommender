package com.baekjoonrec.recommend;

import com.baekjoonrec.auth.User;
import com.baekjoonrec.recommend.dto.RecommendationResponse;
import com.baekjoonrec.recommend.dto.SkipRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/recommend")
@RequiredArgsConstructor
public class RecommendController {

    private final RecommendationService recommendationService;

    @GetMapping
    public ResponseEntity<RecommendationResponse> getRecommendations(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(recommendationService.getRecommendations(user.getId()));
    }

    @PostMapping("/skip")
    public ResponseEntity<Void> skip(@AuthenticationPrincipal User user, @RequestBody SkipRequest request) {
        recommendationService.skipProblem(user.getId(), request.getProblemId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<RecommendationResponse> refresh(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(recommendationService.refreshRecommendations(user.getId()));
    }
}
