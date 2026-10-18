package com.codeoj.codeojbackendquestionservice.submission;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="codeoj.reliable-judge.enabled",havingValue="true")
public class ExecutionRecovery {
    private final ExecutionRepository repository;
    private final ExecutionCommands commands;
    public ExecutionRecovery(ExecutionRepository repository,ExecutionCommands commands) { this.repository=repository;this.commands=commands; }
    @Scheduled(fixedDelayString="${codeoj.reliable-judge.recovery-delay-ms:1000}")
    public void tick() { for(Long id:repository.recoverable()) { commands.recover(id); } }
}
