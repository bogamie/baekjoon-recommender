package com.baekjoonrec.solvedac.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class SolvedacSearchResponse {
    private int count;
    private List<SolvedacProblemItem> items;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SolvedacProblemItem {
        private int problemId;
        private String titleKo;
        private int level;
        private int acceptedUserCount;
        private double averageTries;
        private List<SolvedacTagItem> tags;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SolvedacTagItem {
        private String key;
        private List<SolvedacTagDisplay> displayNames;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SolvedacTagDisplay {
        private String language;
        private String name;
    }
}
