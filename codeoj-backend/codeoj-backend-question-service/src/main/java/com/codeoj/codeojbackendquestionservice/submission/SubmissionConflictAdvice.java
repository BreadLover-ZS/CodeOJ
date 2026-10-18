package com.codeoj.codeojbackendquestionservice.submission;

import com.codeoj.codeojbackendcommon.common.BaseResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DataAccessException;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SubmissionConflictAdvice {
    @ExceptionHandler(SubmissionConflictException.class)
    public ResponseEntity<BaseResponse<?>> conflict(SubmissionConflictException error) {
        return ResponseEntity.status(409).body(new BaseResponse<>(40900,null,error.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<BaseResponse<?>> unavailable(DataAccessException error) {
        // Do not log driver exceptions that may contain SQL parameter excerpts.
        return ResponseEntity.status(503).body(new BaseResponse<>(50010,null,"SUBMISSION_DATABASE_UNAVAILABLE"));
    }
}
