-- After V004. Legacy ordinals remain NULL until closed-gate identity reconciliation.
ALTER TABLE contest_registration_request ADD reservationSequence BIGINT NULL;
CREATE UNIQUE INDEX uk_registration_sequence ON contest_registration_request(contestId,reservationEpoch,reservationSequence);
