package com.baekjoonrec.solvedac.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class SolvedacUserResponse {
    private String handle;
    private int tier;
    private int rating;
    private int solvedCount;
}
