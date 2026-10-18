-- Apply after V003. Existing JUDGE_RESULT outbox events remain replayable.
ALTER TABLE reliable_outbox ADD maxPublishAttempts INT NOT NULL DEFAULT 20;
ALTER TABLE contest ADD registrationReconciledAt DATETIME(6) NULL;

CREATE TABLE contest_scoreboard_state (
    contestId BIGINT PRIMARY KEY,
    activeVersion BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    cacheEpoch BIGINT NOT NULL,
    finalVersion BIGINT NULL,
    asOf DATETIME(6) NOT NULL,
    FOREIGN KEY(contestId) REFERENCES contest(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE contest_scoreboard_version (
    contestId BIGINT NOT NULL,
    projectionVersion BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    digest CHAR(64) NULL,
    createdAt DATETIME(6) NOT NULL,
    asOf DATETIME(6) NOT NULL,
    PRIMARY KEY(contestId,projectionVersion),
    FOREIGN KEY(contestId) REFERENCES contest(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE contest_score_user (
    contestId BIGINT NOT NULL,
    projectionVersion BIGINT NOT NULL,
    userId BIGINT NOT NULL,
    solvedCount INT NOT NULL,
    achievedAt DATETIME(6) NULL,
    PRIMARY KEY(contestId,projectionVersion,userId),
    INDEX ix_score_order(contestId,projectionVersion,solvedCount,achievedAt,userId),
    FOREIGN KEY(contestId,projectionVersion) REFERENCES contest_scoreboard_version(contestId,projectionVersion)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE contest_score_problem (
    contestId BIGINT NOT NULL,
    projectionVersion BIGINT NOT NULL,
    userId BIGINT NOT NULL,
    problemSnapshotId BIGINT NOT NULL,
    firstAcSubmittedAt DATETIME(6) NOT NULL,
    PRIMARY KEY(contestId,projectionVersion,userId,problemSnapshotId),
    FOREIGN KEY(contestId,projectionVersion,userId) REFERENCES contest_score_user(contestId,projectionVersion,userId),
    FOREIGN KEY(problemSnapshotId) REFERENCES contest_problem_snapshot(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE scoreboard_result_receipt (
    eventId CHAR(36) PRIMARY KEY,
    submissionId BIGINT NOT NULL,
    resultVersion INT NOT NULL,
    contestId BIGINT NULL,
    projectionVersion BIGINT NULL,
    processedAt DATETIME(6) NOT NULL,
    FOREIGN KEY(eventId) REFERENCES reliable_outbox(eventId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
