package com.codeoj.codeojbackendquestionservice.submission;

import cn.hutool.json.JSONUtil;
import com.codeoj.codeojbackendmodel.model.reliability.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;

@Repository
public class ExecutionRepository {
    final JdbcTemplate jdbc;

    public ExecutionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Read after acquiring the authority row lock, so lock wait cannot extend an expired lease. */
    public LocalDateTime now() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", (rs, row) -> rs.getObject(1, LocalDateTime.class));
    }

    private final RowMapper<ExecutionRecord> mapper = (rs, row) -> {
        ExecutionRecord r = new ExecutionRecord();
        r.setSubmissionId(rs.getLong("submissionId"));
        r.setUserId(rs.getLong("userId"));
        r.setContestId(rs.getObject("contestId")==null?null:rs.getLong("contestId"));
        r.setRequestKey(rs.getString("requestKey"));
        r.setRequestHash(rs.getString("requestHash"));
        r.setJudgeSpecHash(rs.getString("judgeSpecHash"));
        r.setExecutionStatus(ExecutionStatus.valueOf(rs.getString("executionStatus")));
        r.setDispatchGeneration(rs.getInt("dispatchGeneration"));
        r.setDispatchEventId(rs.getString("dispatchEventId"));
        r.setActiveAttempt(rs.getInt("activeAttempt"));
        r.setExecutionToken(rs.getString("executionToken"));
        r.setLeaseUntil(rs.getObject("leaseUntil", LocalDateTime.class));
        r.setAttemptDeadlineAt(rs.getObject("attemptDeadlineAt", LocalDateTime.class));
        r.setNextRunAt(rs.getObject("nextRunAt", LocalDateTime.class));
        r.setQueuedAt(rs.getObject("queuedAt", LocalDateTime.class));
        r.setAcceptedAt(rs.getObject("acceptedAt", LocalDateTime.class));
        r.setRecoveryDeadlineAt(rs.getObject("recoveryDeadlineAt", LocalDateTime.class));
        r.setMaxAttempts(rs.getInt("maxAttempts"));
        r.setMaxDispatches(rs.getInt("maxDispatches"));
        r.setRecoveryPolicyVersion(rs.getInt("recoveryPolicyVersion"));
        r.setResultVersion(rs.getInt("resultVersion"));
        r.setResultDigest(rs.getString("resultDigest"));
        r.setStateVersion(rs.getLong("stateVersion"));
        r.setLastError(rs.getString("lastError"));
        return r;
    };

    public ExecutionRecord find(long id, boolean lock) {
        List<ExecutionRecord> records = jdbc.query("SELECT * FROM submission_execution WHERE submissionId=?"
                + (lock ? " FOR UPDATE" : ""), mapper, id);
        return records.isEmpty() ? null : records.get(0);
    }

    public ExecutionRecord byKey(long userId, String key) {
        List<ExecutionRecord> records = jdbc.query("SELECT * FROM submission_execution WHERE userId=? AND requestKey=?",
                mapper, userId, key);
        return records.isEmpty() ? null : records.get(0);
    }

    /** Session supplies identity; current account/role is read from the shared business DB. */
    public void requireAccount(long id,String role,boolean lock) {
        List<String> roles=jdbc.queryForList("SELECT userRole FROM `user` WHERE id=? AND isDelete=0"
                +(lock?" FOR UPDATE":""),String.class,id);
        if(roles.isEmpty() || !("user".equals(roles.get(0)) || "admin".equals(roles.get(0)))
                || (role!=null && !role.equals(roles.get(0)))) {
            throw new com.codeoj.codeojbackendcommon.exception.BusinessException(
                    com.codeoj.codeojbackendcommon.common.ErrorCode.FORBIDDEN_ERROR,"ACCOUNT_OR_ROLE_INACTIVE");
        }
    }

    public void save(ExecutionRecord r) {
        int changed = jdbc.update("UPDATE submission_execution SET executionStatus=?,dispatchGeneration=?,dispatchEventId=?,"
                        + "activeAttempt=?,executionToken=?,leaseUntil=?,attemptDeadlineAt=?,nextRunAt=?,queuedAt=?,"
                        + "maxAttempts=?,maxDispatches=?,recoveryDeadlineAt=?,recoveryPolicyVersion=?,resultVersion=?,"
                        + "resultDigest=?,stateVersion=stateVersion+1,lastError=? WHERE submissionId=? AND stateVersion=?",
                r.getExecutionStatus().name(), r.getDispatchGeneration(), r.getDispatchEventId(), r.getActiveAttempt(),
                r.getExecutionToken(), r.getLeaseUntil(), r.getAttemptDeadlineAt(), r.getNextRunAt(), r.getQueuedAt(),
                r.getMaxAttempts(), r.getMaxDispatches(), r.getRecoveryDeadlineAt(), r.getRecoveryPolicyVersion(),
                r.getResultVersion(), r.getResultDigest(), r.getLastError(), r.getSubmissionId(), r.getStateVersion());
        if (changed != 1) { throw new IllegalStateException("EXECUTION_VERSION_CONFLICT"); }
        r.setStateVersion(r.getStateVersion() + 1);
    }

    public JudgeDispatch dispatch(long id, int generation, String eventId) {
        JudgeDispatch message = new JudgeDispatch();
        message.setSubmissionId(id);
        message.setDispatchGeneration(generation);
        message.setEventId(eventId);
        message.setPayloadVersion(1);
        message.setTraceId(eventId);
        return message;
    }

    public void insertDispatch(ExecutionRecord r, LocalDateTime now) {
        jdbc.update("INSERT INTO reliable_outbox(eventId,eventType,aggregateId,aggregateVersion,payloadVersion,payload,"
                        + "publishState,nextRetryAt,attempts) VALUES (?,'JUDGE_DISPATCH',?,?,1,?,'PENDING',?,0)",
                r.getDispatchEventId(), r.getSubmissionId(), r.getDispatchGeneration(),
                JSONUtil.toJsonStr(dispatch(r.getSubmissionId(), r.getDispatchGeneration(), r.getDispatchEventId())), now);
    }

    public List<Long> recoverable() {
        LocalDateTime now=now();
        return jdbc.queryForList("SELECT submissionId FROM submission_execution WHERE executionStatus IN ('QUEUED','RUNNING','RETRY_WAIT')"
                + " AND (recoveryDeadlineAt<=? OR (executionStatus='RUNNING' AND leaseUntil<=?)"
                + " OR (executionStatus='RETRY_WAIT' AND nextRunAt<=?)"
                + " OR (executionStatus='QUEUED' AND queuedAt<=?)) ORDER BY acceptedAt LIMIT 50",Long.class,
                now,now,now,now.minusSeconds(30));
    }

    public void audit(ExecutionRecord r, Long actor, String action, String reason, long before, String details) {
        jdbc.update("INSERT INTO operation_audit(id,actorId,action,targetId,reason,beforeVersion,afterVersion,policyDetails,createdAt)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)", UUID.randomUUID().toString(), actor, action, r.getSubmissionId(),
                reason, before, r.getStateVersion(), details, now());
    }

    public static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : bytes) { result.append(String.format("%02x", b & 0xff)); }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Diagnostic rows exclude secrets in the SELECT list and label UTC dates explicitly. */
    public List<Map<String,Object>> safeRows(String sql,Object... args) {
        return jdbc.query(sql,(rs,row)->{
            Map<String,Object> values=new LinkedHashMap<>();
            java.sql.ResultSetMetaData metadata=rs.getMetaData();
            for(int i=1;i<=metadata.getColumnCount();i++) {
                Object value=rs.getObject(i);
                if(value instanceof java.sql.Timestamp) {
                    value=rs.getObject(i,LocalDateTime.class).toInstant(java.time.ZoneOffset.UTC).toString();
                } else if(value instanceof java.sql.Clob) {
                    value=rs.getString(i);
                }
                values.put(metadata.getColumnLabel(i),value);
            }
            return values;
        },args);
    }
}
