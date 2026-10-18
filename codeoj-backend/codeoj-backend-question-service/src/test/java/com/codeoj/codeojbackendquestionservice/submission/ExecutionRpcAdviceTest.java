package com.codeoj.codeojbackendquestionservice.submission;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionRpcAdviceTest {
    @Test void authorityDiagnosticsRetainTypeLocationAndSqlCodeWithoutSensitiveMessages() {
        Logger logger=(Logger)LoggerFactory.getLogger(ExecutionRpcAdvice.class);ListAppender<ILoggingEvent> appender=new ListAppender<>();appender.start();logger.addAppender(appender);
        try {
            RuntimeException error=new IllegalStateException("PRIVATE_SOURCE_AND_PASSWORD",new SQLException("HIDDEN_CASE_AND_SQL_VALUES","23000",1062));
            assertEquals(503,new ExecutionRpcAdvice().unavailable(error).getStatusCodeValue());
            assertEquals(1,appender.list.size());ILoggingEvent event=appender.list.get(0);String message=event.getFormattedMessage();
            assertTrue(message.contains("IllegalStateException"));assertTrue(message.contains("ExecutionRpcAdviceTest"));assertTrue(message.contains("SQL_STATE=23000;CODE=1062"));
            assertFalse(message.contains("PRIVATE_SOURCE"));assertFalse(message.contains("HIDDEN_CASE"));assertNull(event.getThrowableProxy());
        } finally { logger.detachAppender(appender);appender.stop(); }
    }
    @Test void untrustedSqlStateIsReducedToUnknown() {
        Logger logger=(Logger)LoggerFactory.getLogger(ExecutionRpcAdvice.class);ListAppender<ILoggingEvent> appender=new ListAppender<>();appender.start();logger.addAppender(appender);
        try {
            new ExecutionRpcAdvice().unavailable(new IllegalStateException(new SQLException("PRIVATE_MESSAGE","PRIVATE_STATE_VALUE",0)));
            String message=appender.list.get(0).getFormattedMessage();assertTrue(message.contains("SQL_STATE=UNKNOWN"));assertFalse(message.contains("PRIVATE_"));
        } finally { logger.detachAppender(appender);appender.stop(); }
    }
}
