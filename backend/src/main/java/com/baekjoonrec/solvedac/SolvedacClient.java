package com.baekjoonrec.solvedac;

import com.baekjoonrec.solvedac.dto.SolvedacProblemResponse;
import com.baekjoonrec.solvedac.dto.SolvedacSearchResponse;
import com.baekjoonrec.solvedac.dto.SolvedacUserResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class SolvedacClient {

    private static final String BASE_URL = "https://solved.ac/api/v3";
    private final RestClient restClient;

    public SolvedacClient() {
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .build();
    }

    public SolvedacUserResponse getUser(String handle) {
        rateLimit();
        return restClient.get()
                .uri("/user/show?handle={handle}", handle)
                .retrieve()
                .body(SolvedacUserResponse.class);
    }

    public SolvedacSearchResponse searchSolvedProblems(String handle, int page) {
        rateLimit();
        return restClient.get()
                .uri("/search/problem?query=solved_by:{handle}&sort=level&direction=asc&page={page}",
                        handle, page)
                .retrieve()
                .body(SolvedacSearchResponse.class);
    }

    public SolvedacProblemResponse getProblem(int problemId) {
        rateLimit();
        return restClient.get()
                .uri("/problem/show?problemId={id}", problemId)
                .retrieve()
                .body(SolvedacProblemResponse.class);
    }

    /**
     * Search problems by query string (supports tag: and level filters).
     * Example query: "tag:dp level:6..15"
     */
    public SolvedacSearchResponse searchProblems(String query, int page) {
        rateLimit();
        return restClient.get()
                .uri("/search/problem?query={query}&sort=level&direction=asc&page={page}",
                        query, page)
                .retrieve()
                .body(SolvedacSearchResponse.class);
    }

    private void rateLimit() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
