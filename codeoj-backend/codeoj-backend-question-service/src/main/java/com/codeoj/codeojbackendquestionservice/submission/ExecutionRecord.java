package com.codeoj.codeojbackendquestionservice.submission;

import com.codeoj.codeojbackendmodel.model.reliability.ExecutionStatus;
import lombok.Data;
import java.time.LocalDateTime;

/** Database authority. Never expose tokens or private specification references in public responses. */
@Data
public class ExecutionRecord {
    private long submissionId;
    private long userId;
    private Long contestId;
    private String requestKey;
    private String requestHash;
    private String judgeSpecHash;
    private ExecutionStatus executionStatus;
    private int dispatchGeneration;
    private String dispatchEventId;
    private int activeAttempt;
    private String executionToken;
    private LocalDateTime leaseUntil;
    private LocalDateTime attemptDeadlineAt;
    private LocalDateTime nextRunAt;
    private LocalDateTime queuedAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime recoveryDeadlineAt;
    private int maxAttempts;
    private int maxDispatches;
    private int recoveryPolicyVersion;
    private int resultVersion;
    private String resultDigest;
    private long stateVersion;
    private String lastError;
}
