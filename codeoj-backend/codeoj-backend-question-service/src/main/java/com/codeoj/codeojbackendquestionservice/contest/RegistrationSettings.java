package com.codeoj.codeojbackendquestionservice.contest;
import lombok.Data;
import java.time.LocalDateTime;
@Data
public class RegistrationSettings {
    private long contestId,publishedVersion,epoch,revision;
    private int initialCapacity;
    private boolean initialized,ready;
    private String mode,status,issue;
    private LocalDateTime open,close,now;
    public boolean gateOpen() { return ready && "PUBLISHED".equals(status) && "REDIS_READY".equals(mode) && issue==null; }
}
