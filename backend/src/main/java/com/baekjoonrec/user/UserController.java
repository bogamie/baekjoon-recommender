package com.baekjoonrec.user;

import com.baekjoonrec.auth.User;
import com.baekjoonrec.solvedac.SolvedacSyncService;
import com.baekjoonrec.user.dto.DeleteAccountRequest;
import com.baekjoonrec.user.dto.UpdateHandleRequest;
import com.baekjoonrec.user.dto.UpdateSettingsRequest;
import com.baekjoonrec.user.dto.UserInfoResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final SolvedacSyncService solvedacSyncService;

    @GetMapping("/me")
    public ResponseEntity<UserInfoResponse> me(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(userService.getCurrentUser(user.getId()));
    }

    @PutMapping("/me/solvedac")
    public ResponseEntity<UserInfoResponse> updateHandle(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody UpdateHandleRequest request) {
        UserInfoResponse response = userService.updateSolvedacHandle(user.getId(), request);
        solvedacSyncService.resetSyncTime(user.getId());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/me/settings")
    public ResponseEntity<UserInfoResponse> updateSettings(
            @AuthenticationPrincipal User user,
            @RequestBody UpdateSettingsRequest request) {
        return ResponseEntity.ok(userService.updateSettings(user.getId(), request));
    }

    @PostMapping("/me/sync")
    public ResponseEntity<Map<String, String>> sync(@AuthenticationPrincipal User user) {
        solvedacSyncService.syncUserSolvedProblems(user.getId());
        return ResponseEntity.ok(Map.of("message", "Sync completed"));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Map<String, String>> deleteAccount(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody DeleteAccountRequest request) {
        userService.deleteAccount(user.getId(), request.getPassword());
        return ResponseEntity.ok(Map.of("message", "Account deleted successfully"));
    }
}
