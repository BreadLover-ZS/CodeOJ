package com.codeoj.codeojbackendquestionservice.scoreboard;

import lombok.Data;

/** Compatible with the result payload already persisted by J2. Identity is the AMQP messageId. */
@Data
public class ScoreResultEvent {
    private Long submissionId;
    private Integer resultVersion;
    public boolean valid() { return submissionId!=null && submissionId>0 && resultVersion!=null && resultVersion>0; }
}
