package com.baekjoonrec.solvedac;

import com.baekjoonrec.solvedac.dto.SolvedacProblemResponse;
import com.baekjoonrec.solvedac.dto.SolvedacSearchResponse;
import com.baekjoonrec.solvedac.dto.SolvedacUserResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
public class SolvedacClient {

    private static final String BASE_URL = "https://solved.ac/api/v3";
    private static final int MAX_RETRIES = 3;
    private final RestClient restClient;

    public SolvedacClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(30_000);

        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .requestFactory(factory)
                .build();
    }

    public SolvedacUserResponse getUser(String handle) {
        return executeWithRetry(() -> {
            rateLimit();
            return restClient.get()
                    .uri("/user/show?handle={handle}", handle)
                    .retrieve()
                    .body(SolvedacUserResponse.class);
        });
    }

    public SolvedacSearchResponse searchSolvedProblems(String handle, int page) {
        return executeWithRetry(() -> {
            rateLimit();
            return restClient.get()
                    .uri("/search/problem?query=solved_by:{handle}&sort=level&direction=asc&page={page}",
                            handle, page)
                    .retrieve()
                    .body(SolvedacSearchResponse.class);
        });
    }

    public SolvedacProblemResponse getProblem(int problemId) {
        return executeWithRetry(() -> {
            rateLimit();
            return restClient.get()
                    .uri("/problem/show?problemId={id}", problemId)
                    .retrieve()
                    .body(SolvedacProblemResponse.class);
        });
    }

    public SolvedacSearchResponse searchProblems(String query, int page) {
        return executeWithRetry(() -> {
            rateLimit();
            return restClient.get()
                    .uri("/search/problem?query={query}&sort=level&direction=asc&page={page}",
                            query, page)
                    .retrieve()
                    .body(SolvedacSearchResponse.class);
        });
    }

    private <T> T executeWithRetry(java.util.function.Supplier<T> action) {
        RestClientException lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return action.get();
            } catch (RestClientException e) {
                lastException = e;
                log.warn("solved.ac API call failed (attempt {}/{}): {}", attempt, MAX_RETRIES, e.getMessage());
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(1000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw lastException;
    }

    private void rateLimit() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
