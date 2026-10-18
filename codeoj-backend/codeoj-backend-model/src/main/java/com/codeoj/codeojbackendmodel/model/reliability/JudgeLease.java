package com.codeoj.codeojbackendmodel.model.reliability;

import com.codeoj.codeojbackendmodel.model.dto.question.JudgeCase;
import com.codeoj.codeojbackendmodel.model.dto.question.JudgeConfig;
import lombok.Data;
import java.util.List;

/** Private internal response, never returned through a user-facing submission endpoint. */
@Data
public class JudgeLease {
    private Boolean claimed;

    public boolean isClaimed() { return Boolean.TRUE.equals(claimed); }
    private Long submissionId;
    private Integer attemptNo;
    private String executionToken;
    private Long deadlineEpochMs;
    private String language;
    private String code;
    private List<JudgeCase> cases;
    private JudgeConfig limits;
    private String judgeSpecHash;
}
