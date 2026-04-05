package com.baekjoonrec.solvedac.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class SolvedacHistoryEntry {
    private String timestamp;
    private int value;
}
