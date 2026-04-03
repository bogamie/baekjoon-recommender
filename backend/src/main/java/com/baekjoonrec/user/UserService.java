package com.baekjoonrec.user;

import com.baekjoonrec.analysis.UserAnalysisRepository;
import com.baekjoonrec.analysis.UserTagStatRepository;
import com.baekjoonrec.auth.User;
import com.baekjoonrec.auth.UserRepository;
import com.baekjoonrec.common.ApiException;
import com.baekjoonrec.problem.UserSolvedRepository;
import com.baekjoonrec.recommend.RecommendationService;
import com.baekjoonrec.user.dto.UpdateHandleRequest;
import com.baekjoonrec.user.dto.UpdateSettingsRequest;
import com.baekjoonrec.user.dto.UserInfoResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserSolvedRepository userSolvedRepository;
    private final UserAnalysisRepository userAnalysisRepository;
    private final UserTagStatRepository userTagStatRepository;
    private final RecommendationService recommendationService;

    public UserInfoResponse getCurrentUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));
        return toResponse(user);
    }

    @Transactional
    public UserInfoResponse updateSolvedacHandle(Long userId, UpdateHandleRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));

        String oldHandle = user.getSolvedacHandle();
        String newHandle = request.getHandle();

        // Clear old account data when handle actually changes
        if (oldHandle != null && !oldHandle.equals(newHandle)) {
            userSolvedRepository.deleteByUserId(userId);
            userTagStatRepository.deleteByUserId(userId);
            userAnalysisRepository.deleteById(userId);
            recommendationService.invalidateRecommendations(userId);
        }

        user.setSolvedacHandle(newHandle);
        userRepository.save(user);
        return toResponse(user);
    }

    @Transactional
    public UserInfoResponse updateSettings(Long userId, UpdateSettingsRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));
        if (request.getTheme() != null) {
            user.setTheme(request.getTheme());
        }
        if (request.getIncludeForeign() != null) {
            user.setIncludeForeign(request.getIncludeForeign());
        }
        userRepository.save(user);
        return toResponse(user);
    }

    private UserInfoResponse toResponse(User user) {
        return UserInfoResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .username(user.getUsername())
                .solvedacHandle(user.getSolvedacHandle())
                .emailVerified(user.getEmailVerified())
                .theme(user.getTheme())
                .includeForeign(user.getIncludeForeign())
                .build();
    }
}
