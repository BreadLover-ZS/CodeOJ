package com.codeoj.codeojbackendquestionservice.submission;

import com.codeoj.codeojbackendcommon.common.BaseResponse;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendquestionservice.controller.inner.QuestionInnerController;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** A failed authority command must not be decoded as a default false claim at HTTP 200. */
@RestControllerAdvice(assignableTypes={QuestionInnerController.class,ExecutionAdminController.class,SubmissionStatusController.class,
        com.codeoj.codeojbackendquestionservice.contest.ContestController.class,com.codeoj.codeojbackendquestionservice.contest.RegistrationAdminController.class})
@Order(Ordered.HIGHEST_PRECEDENCE+1)
public class ExecutionRpcAdvice {
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<BaseResponse<?>> business(BusinessException error) {
        int code=error.getCode();
        int status=code==40000?400:code==40100?401:(code==40101 || code==40300)?403:code==40400?404:503;
        return ResponseEntity.status(status).body(new BaseResponse<>(error.getCode(),null,error.getMessage()));
    }
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<BaseResponse<?>> unavailable(RuntimeException error) {
        return ResponseEntity.status(503).body(new BaseResponse<>(50010,null,"EXECUTION_AUTHORITY_UNAVAILABLE"));
    }
}
