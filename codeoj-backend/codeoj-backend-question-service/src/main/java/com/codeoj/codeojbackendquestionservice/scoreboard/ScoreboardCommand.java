package com.codeoj.codeojbackendquestionservice.scoreboard;

import lombok.Data;

@Data
public class ScoreboardCommand {
    private Long expectedVersion;
    private Long expectedProjectionVersion;
    private Long expectedRevision;
    private String reason;
    private int additionalPublishAttempts;
}
