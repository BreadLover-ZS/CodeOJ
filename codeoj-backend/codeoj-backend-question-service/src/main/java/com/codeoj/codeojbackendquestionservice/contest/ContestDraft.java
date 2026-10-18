package com.codeoj.codeojbackendquestionservice.contest;

import com.codeoj.codeojbackendmodel.model.dto.question.*;
import lombok.Data;
import java.util.List;

@Data
public class ContestDraft {
    private Long expectedVersion;
    private String title;
    /** PRIVATE: inline contest-only statements/specs. PUBLIC_TRAINING: existing public question IDs. */
    private String contestType;
    private Integer capacity;
    /** Explicit ISO-8601 instants, e.g. 2026-10-20T01:00:00Z. */
    private String registrationOpenAt;
    private String registrationCloseAt;
    private String startAt;
    private String endAt;
    private List<Problem> problems;

    @Data
    public static class Problem {
        private String problemCode;
        private Long sourceQuestionId;
        private String title;
        private String content;
        private JudgeConfig limits;
        private List<JudgeCase> cases;
    }
}
