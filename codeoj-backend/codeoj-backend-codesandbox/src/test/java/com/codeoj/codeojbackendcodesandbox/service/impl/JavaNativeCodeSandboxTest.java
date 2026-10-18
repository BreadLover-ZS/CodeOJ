package com.codeoj.codeojbackendcodesandbox.service.impl;

import com.codeoj.codeojbackendmodel.model.codesandbox.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/** Real javac/java child processes. No Spring, MySQL, Redis, RabbitMQ or Docker required. */
@Timeout(15)
class JavaNativeCodeSandboxTest {
    private JavaNativeCodeSandbox sandbox() {
        JavaNativeCodeSandbox sandbox = new JavaNativeCodeSandbox(1);
        ReflectionTestUtils.setField(sandbox, "runTimeoutMs", 800L);
        return sandbox;
    }

    private ExecuteCodeRequest request(String body, String... inputs) {
        return ExecuteCodeRequest.builder().language("java")
                .code("public class Main { public static void main(String[] args) throws Exception { " + body + " } }")
                .inputList(Arrays.asList(inputs)).build();
    }

    @Test void compilesAndExecutesWithoutInventingMemoryMeasurement() {
        ExecuteCodeResponse response = sandbox().executeCode(request(
                "java.util.Scanner s = new java.util.Scanner(System.in); System.out.println(s.nextInt()+s.nextInt());",
                "1 2", "3 4"));
        assertEquals(SandboxOutcome.EXECUTED, response.getOutcome());
        assertEquals(Arrays.asList("3\n", "7\n"), response.getOutputList());
        assertNotNull(response.getJudgeInfo().getTime());
        assertTrue(response.getJudgeInfo().getMemory() == null || response.getJudgeInfo().getMemory() > 0);
        assertNull(response.getJudgeInfo().getVerdict());
    }

    @Test void explicitCompilerDiagnosticIsCe() {
        ExecuteCodeResponse response = sandbox().executeCode(request("this does not compile;", ""));
        assertEquals(SandboxOutcome.CE, response.getOutcome());
        assertEquals(2, response.getStatus());
    }

    @Test void silentInfiniteLoopReachesTrustedRunDeadline() {
        ExecuteCodeResponse response = sandbox().executeCode(request("while(true) {}", ""));
        assertEquals(SandboxOutcome.TLE, response.getOutcome());
    }

    @Test void continuousStderrIsTerminatedAsOutputLimit() {
        ExecuteCodeResponse response = sandbox().executeCode(request(
                "while(true) { System.err.print(\"abcdefghijklmnopqrstuvwxyz0123456789\"); }", ""));
        assertEquals(SandboxOutcome.OUTPUT_LIMIT, response.getOutcome());
        assertTrue(response.getOutputList().isEmpty());
    }

    @Test void stdoutAndStderrAreDrainedConcurrently() {
        ExecuteCodeResponse response = sandbox().executeCode(request(
                "for(int i=0;i<10000;i++){ System.out.print('x'); System.err.print('y'); }", ""));
        assertEquals(SandboxOutcome.EXECUTED, response.getOutcome());
        assertEquals(10000, response.getOutputList().get(0).length());
    }

    @Test void blockedStdinDoesNotBlockDeadlineOrNextSubmission() {
        JavaNativeCodeSandbox sandbox = sandbox();
        char[] input = new char[1024 * 1024];
        Arrays.fill(input, 'x');
        ExecuteCodeResponse response = sandbox.executeCode(request("while(true) {}", new String(input)));
        assertEquals(SandboxOutcome.TLE, response.getOutcome());
        assertEquals(SandboxOutcome.EXECUTED,
                sandbox.executeCode(request("System.out.print(1);", "")).getOutcome());
    }

    @Test void eachCaseGetsAFreshWorkingDirectoryAndJvm() {
        ExecuteCodeResponse response = sandbox().executeCode(request(
                "java.io.File f=new java.io.File(\"marker\"); System.out.print(f.exists()); f.createNewFile();", "", ""));
        assertEquals(SandboxOutcome.EXECUTED, response.getOutcome());
        assertEquals(Arrays.asList("false", "false"), response.getOutputList());
    }

    @Test void unknownExitIsPlatformFailureRatherThanGuessedMleOrRe() {
        ExecuteCodeResponse response = sandbox().executeCode(request("System.exit(7);", ""));
        assertEquals(SandboxOutcome.PLATFORM_ERROR, response.getOutcome());
        assertEquals("UNCLASSIFIED_PROCESS_EXIT", response.getErrorCode());
    }

    @Test void attemptBudgetCannotBeMisreportedAsUserTle() {
        JavaNativeCodeSandbox sandbox = sandbox();
        ReflectionTestUtils.setField(sandbox, "attemptTimeoutMs", 1L);
        ExecuteCodeResponse response = sandbox.executeCode(request("System.out.print(1);", ""));
        assertEquals(SandboxOutcome.PLATFORM_ERROR, response.getOutcome());
    }

    @Test void smallerExecutorPolicyCannotSilentlyOverrideProblemTimeLimit() {
        ExecuteCodeRequest request = request("", "");
        request.setRunTimeLimitMs(900L); // The test executor is configured for 800ms.
        ExecuteCodeResponse response = sandbox().executeCode(request);
        assertEquals(SandboxOutcome.PLATFORM_ERROR, response.getOutcome());
        assertEquals("CASE_LIMIT_EXCEEDS_EXECUTOR_POLICY", response.getErrorCode());
    }

    @Test void validatesCaseCountLanguageNullsAndInputBudget() {
        assertEquals(SandboxOutcome.REJECTED, sandbox().executeCode(null).getOutcome());
        ExecuteCodeRequest request = request("", "");
        request.setLanguage("cpp");
        assertEquals(SandboxOutcome.REJECTED, sandbox().executeCode(request).getOutcome());
        request.setLanguage("java");
        request.setInputList(Collections.nCopies(21, ""));
        assertEquals(SandboxOutcome.REJECTED, sandbox().executeCode(request).getOutcome());
        request.setInputList(Collections.singletonList(null));
        assertEquals(SandboxOutcome.REJECTED, sandbox().executeCode(request).getOutcome());
        request.setInputList(Collections.singletonList(new String(new char[1024 * 1024 + 1])));
        assertEquals(SandboxOutcome.REJECTED, sandbox().executeCode(request).getOutcome());
    }

    @Test
    @EnabledIfSystemProperty(named = "codeoj.native-observation", matches = "true")
    void measuresActualJvmRssWhenHostObservationIsAvailable() {
        ExecuteCodeResponse response = sandbox().executeCode(request("Thread.sleep(150); System.out.print(1);", ""));
        assertEquals(SandboxOutcome.EXECUTED, response.getOutcome());
        assertNotNull(response.getJudgeInfo().getMemory());
        assertTrue(response.getJudgeInfo().getMemory() > 0);
        assertEquals("NATIVE_RSS_SAMPLED", response.getJudgeInfo().getMemoryMeasurement());
    }

    @Test
    @EnabledIfSystemProperty(named = "codeoj.native-observation", matches = "true")
    void observedRssBreachHasExplicitMleEvidence() {
        ExecuteCodeRequest request = request("Thread.sleep(300);", "");
        request.setMemoryLimitKiB(1L);
        ExecuteCodeResponse response = sandbox().executeCode(request);
        assertEquals(SandboxOutcome.MLE, response.getOutcome());
        assertTrue(response.getJudgeInfo().getMemory() > 1L);
        assertEquals("OBSERVED_RSS_LIMIT_EXCEEDED", response.getErrorCode());
    }
}
