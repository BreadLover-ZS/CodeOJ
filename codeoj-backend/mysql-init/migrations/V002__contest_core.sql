-- Apply after V001. No historical data is reclassified as contest data.
CREATE TABLE contest (
    id BIGINT PRIMARY KEY,
    title VARCHAR(120) NOT NULL,
    contestType VARCHAR(32) NOT NULL,
    capacity INT NOT NULL,
    remainingCapacity INT NOT NULL,
    registrationOpenAt DATETIME(6) NOT NULL,
    registrationCloseAt DATETIME(6) NOT NULL,
    startAt DATETIME(6) NOT NULL,
    endAt DATETIME(6) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL,
    publishedVersion BIGINT NULL,
    registrationReady BOOLEAN NOT NULL,
    ruleVersion VARCHAR(32) NOT NULL,
    registrationMode VARCHAR(32) NOT NULL,
    draftPayload LONGTEXT NOT NULL,
    createdBy BIGINT NOT NULL,
    createdAt DATETIME(6) NOT NULL,
    CHECK(capacity>0 AND remainingCapacity>=0 AND remainingCapacity<=capacity),
    CHECK(registrationOpenAt<registrationCloseAt AND registrationCloseAt<startAt AND startAt<endAt),
    INDEX ix_contest_schedule(status,endAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Private contest problems NEVER become ordinary question rows, even while drafting.
CREATE TABLE contest_problem_snapshot (
    id BIGINT PRIMARY KEY,
    contestId BIGINT NOT NULL,
    publishedVersion BIGINT NOT NULL,
    problemCode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sourceQuestionId BIGINT NULL,
    title VARCHAR(120) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    judgeSpecHash CHAR(64) NOT NULL,
    snapshotHash CHAR(64) NOT NULL,
    UNIQUE KEY uk_contest_problem(contestId,publishedVersion,problemCode),
    UNIQUE KEY uk_snapshot_context(contestId,publishedVersion,id),
    FOREIGN KEY(contestId) REFERENCES contest(id),
    FOREIGN KEY(judgeSpecHash) REFERENCES judge_spec(specHash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE contest_registration_request (
    id BIGINT PRIMARY KEY,
    contestId BIGINT NOT NULL,
    publishedVersion BIGINT NOT NULL,
    userId BIGINT NOT NULL,
    eventId CHAR(36) NOT NULL,
    requestKey VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requestHash CHAR(64) NOT NULL,
    reservedAt DATETIME(6) NOT NULL,
    status VARCHAR(32) NOT NULL,
    stateVersion BIGINT NOT NULL,
    reclaimState VARCHAR(32) NOT NULL,
    lastError VARCHAR(128) NULL,
    createdAt DATETIME(6) NOT NULL,
    UNIQUE KEY uk_registration_user(contestId,userId),
    UNIQUE KEY uk_registration_event(eventId),
    INDEX ix_registration_pending(status,createdAt),
    FOREIGN KEY(contestId) REFERENCES contest(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE contest_registration (
    id BIGINT PRIMARY KEY,
    contestId BIGINT NOT NULL,
    userId BIGINT NOT NULL,
    requestId BIGINT NOT NULL,
    confirmedAt DATETIME(6) NOT NULL,
    status VARCHAR(32) NOT NULL,
    UNIQUE KEY uk_qualification(contestId,userId),
    UNIQUE KEY uk_qualification_request(requestId),
    FOREIGN KEY(contestId) REFERENCES contest(id),
    FOREIGN KEY(requestId) REFERENCES contest_registration_request(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE submission_execution ADD problemSnapshotId BIGINT NULL;
ALTER TABLE submission_execution ADD publishedVersion BIGINT NULL;
CREATE INDEX ix_contest_submissions ON submission_execution(contestId,userId,problemSnapshotId,acceptedAt);
ALTER TABLE submission_execution ADD CONSTRAINT fk_submission_snapshot FOREIGN KEY(contestId,publishedVersion,problemSnapshotId)
        REFERENCES contest_problem_snapshot(contestId,publishedVersion,id);
