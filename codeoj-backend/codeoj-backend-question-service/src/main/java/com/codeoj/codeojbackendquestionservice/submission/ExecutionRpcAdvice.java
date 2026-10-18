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
        com.codeoj.codeojbackendquestionservice.contest.ContestController.class,com.codeoj.codeojbackendquestionservice.contest.RegistrationAdminController.class,
        com.codeoj.codeojbackendquestionservice.scoreboard.ScoreboardController.class})
@Order(Ordered.HIGHEST_PRECEDENCE+1)
@lombok.extern.slf4j.Slf4j
public class ExecutionRpcAdvice {
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<BaseResponse<?>> business(BusinessException error) {
        int code=error.getCode();
        int status=code==40000?400:code==40100?401:(code==40101 || code==40300)?403:code==40400?404:503;
        return ResponseEntity.status(status).body(new BaseResponse<>(error.getCode(),null,error.getMessage()));
    }
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<BaseResponse<?>> unavailable(RuntimeException error) {
        // Driver messages/Throwable logging may contain SQL values, source code or credentials.
        java.util.List<String> diagnostic=new java.util.ArrayList<>();
        Throwable cause=error;
        for(int depth=0;cause!=null && depth<5;depth++,cause=cause.getCause()) {
            diagnostic.add(cause.getClass().getName());
            for(StackTraceElement frame:cause.getStackTrace()) {
                if(frame.getClassName().startsWith("com.codeoj.")) {
                    diagnostic.add(frame.getClassName()+"."+frame.getMethodName()+":"+frame.getLineNumber());break;
                }
            }
            if(cause instanceof java.sql.SQLException) {
                java.sql.SQLException sql=(java.sql.SQLException)cause;
                String state=sql.getSQLState();
                diagnostic.add("SQL_STATE="+(state!=null && state.matches("[A-Z0-9]{5}")?state:"UNKNOWN")+";CODE="+sql.getErrorCode());
            }
        }
        log.error("Execution authority unavailable: {}",diagnostic);
        return ResponseEntity.status(503).body(new BaseResponse<>(50010,null,"EXECUTION_AUTHORITY_UNAVAILABLE"));
    }
}
