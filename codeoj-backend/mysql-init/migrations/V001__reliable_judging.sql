-- Expand only. Run against the existing business database; never reset existing data.
-- Critical dates are UTC DATETIME(6), explicitly written from UTC_TIMESTAMP(6).
ALTER TABLE question_submit MODIFY code MEDIUMTEXT NOT NULL;
CREATE TABLE judge_spec (
    specHash CHAR(64) PRIMARY KEY,
    payload LONGTEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Sidecar authority for NEW submissions; legacy question_submit rows keep their old semantics.
CREATE TABLE submission_execution (
    submissionId BIGINT PRIMARY KEY,
    userId BIGINT NOT NULL,
    requestKey VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requestHash CHAR(64) NOT NULL,
    judgeSpecHash CHAR(64) NOT NULL,
    contestId BIGINT NULL,
    executionStatus VARCHAR(32) NOT NULL,
    dispatchGeneration INT NOT NULL,
    dispatchEventId CHAR(36) NOT NULL,
    activeAttempt INT NOT NULL,
    executionToken CHAR(36) NULL,
    leaseUntil DATETIME(6) NULL,
    attemptDeadlineAt DATETIME(6) NULL,
    nextRunAt DATETIME(6) NOT NULL,
    queuedAt DATETIME(6) NOT NULL,
    acceptedAt DATETIME(6) NOT NULL,
    recoveryDeadlineAt DATETIME(6) NOT NULL,
    maxAttempts INT NOT NULL,
    maxDispatches INT NOT NULL,
    recoveryPolicyVersion INT NOT NULL,
    resultVersion INT NOT NULL,
    resultDigest CHAR(64) NULL,
    stateVersion BIGINT NOT NULL,
    lastError VARCHAR(128) NULL,
    UNIQUE KEY uk_submission_request (userId, requestKey),
    INDEX ix_execution_lease (executionStatus, leaseUntil),
    INDEX ix_execution_due (executionStatus, nextRunAt),
    INDEX ix_execution_queued (executionStatus, queuedAt),
    CONSTRAINT fk_execution_submission FOREIGN KEY (submissionId) REFERENCES question_submit(id),
    CONSTRAINT fk_execution_spec FOREIGN KEY (judgeSpecHash) REFERENCES judge_spec(specHash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE judge_attempt (
    submissionId BIGINT NOT NULL,
    attemptNo INT NOT NULL,
    executionToken CHAR(36) NOT NULL,
    state VARCHAR(32) NOT NULL,
    startedAt DATETIME(6) NOT NULL,
    deadlineAt DATETIME(6) NOT NULL,
    finishedAt DATETIME(6) NULL,
    errorCode VARCHAR(128) NULL,
    resultDigest CHAR(64) NULL,
    resourceMetrics TEXT NULL,
    PRIMARY KEY (submissionId, attemptNo),
    UNIQUE KEY uk_attempt_token (executionToken),
    CONSTRAINT fk_attempt_submission FOREIGN KEY (submissionId) REFERENCES question_submit(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE reliable_outbox (
    eventId CHAR(36) PRIMARY KEY,
    eventType VARCHAR(32) NOT NULL,
    aggregateId BIGINT NOT NULL,
    aggregateVersion INT NOT NULL,
    payloadVersion INT NOT NULL,
    payload TEXT NOT NULL,
    publishState VARCHAR(32) NOT NULL,
    leaseToken CHAR(36) NULL,
    leaseUntil DATETIME(6) NULL,
    nextRetryAt DATETIME(6) NOT NULL,
    attempts INT NOT NULL,
    lastError VARCHAR(128) NULL,
    UNIQUE KEY uk_outbox_generation (eventType, aggregateId, aggregateVersion),
    INDEX ix_outbox_due (publishState, nextRetryAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE publish_attempt (
    eventId CHAR(36) NOT NULL,
    attemptNo INT NOT NULL,
    attemptToken CHAR(36) NOT NULL,
    confirmState VARCHAR(16) NOT NULL,
    returned BOOLEAN NOT NULL,
    errorCode VARCHAR(128) NULL,
    createdAt DATETIME(6) NOT NULL,
    PRIMARY KEY (eventId, attemptNo),
    UNIQUE KEY uk_publish_token (attemptToken),
    CONSTRAINT fk_publish_event FOREIGN KEY (eventId) REFERENCES reliable_outbox(eventId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE operation_audit (
    id CHAR(36) PRIMARY KEY,
    actorId BIGINT NULL,
    action VARCHAR(64) NOT NULL,
    targetId BIGINT NOT NULL,
    reason VARCHAR(512) NOT NULL,
    beforeVersion BIGINT NOT NULL,
    afterVersion BIGINT NOT NULL,
    policyDetails TEXT NULL,
    createdAt DATETIME(6) NOT NULL,
    INDEX ix_audit_target (targetId, createdAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
