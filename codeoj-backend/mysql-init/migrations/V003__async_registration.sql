-- Expand after V002. Existing contests keep DB_SYNC until an audited, closed-gate cutover.
ALTER TABLE contest ADD registrationEpoch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE contest ADD registrationInitialized BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE contest ADD initialRedisCapacity INT NULL;
ALTER TABLE contest ADD registrationIssue VARCHAR(128) NULL;
ALTER TABLE contest_registration_request ADD reservationEpoch BIGINT NULL;
ALTER TABLE contest_registration_request ADD confirmationChecks INT NOT NULL DEFAULT 0;
ALTER TABLE contest_registration_request ADD maxPublishAttempts INT NOT NULL DEFAULT 20;
ALTER TABLE contest_registration_request ADD nextActionAt DATETIME(6) NULL;
ALTER TABLE contest_registration_request ADD confirmationDeadlineAt DATETIME(6) NULL;
CREATE INDEX ix_registration_actions ON contest_registration_request(status,nextActionAt);
