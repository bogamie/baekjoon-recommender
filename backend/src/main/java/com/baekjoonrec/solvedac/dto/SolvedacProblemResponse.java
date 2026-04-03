package com.baekjoonrec.solvedac.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class SolvedacProblemResponse {
    private int problemId;
    private String titleKo;
    private int level;
    private int acceptedUserCount;
    private double averageTries;
    private List<SolvedacSearchResponse.SolvedacTagItem> tags;
}
