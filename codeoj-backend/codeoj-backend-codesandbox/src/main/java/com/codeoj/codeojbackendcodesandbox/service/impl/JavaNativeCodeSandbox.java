package com.codeoj.codeojbackendcodesandbox.service.impl;

import cn.hutool.core.util.StrUtil;
import com.codeoj.codeojbackendcodesandbox.service.CodeSandboxService;
import com.codeoj.codeojbackendmodel.model.codesandbox.ExecuteCodeRequest;
import com.codeoj.codeojbackendmodel.model.codesandbox.ExecuteCodeResponse;
import com.codeoj.codeojbackendmodel.model.codesandbox.JudgeInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Java 原生代码沙箱实现
 *
 * <p>流程：临时目录写入源码 → javac 编译 → 逐用例 java 运行（stdin 喂入）→ 采集 stdout。
 * 包含鉴权、代码/输出大小限制、编译与运行超时、临时目录清理等防护。
 *
 * <p>安全说明：本实现为进程级隔离（超时 + 资源回收），适合本地开发与内网演示；
 * 生产环境建议部署在独立容器/独立宿主机中（如 docker run 限制 CPU/内存/网络），
 * 并仅允许判题服务内网访问。
 */
@Slf4j
@Service
public class JavaNativeCodeSandbox implements CodeSandboxService {

    /**
     * 鉴权请求头约定值（判题服务 RemoteCodeSandbox 同名常量）
     */
    private static final String AUTH_HEADER = "auth";

    /**
     * 与判题服务一致的执行状态值：1 成功 / 2 失败
     */
    private static final int STATUS_SUCCESS = 1;
    private static final int STATUS_FAILED = 2;

    /**
     * 单个用例最大输出字符数（防止刷屏式输出占满内存/磁盘）
     */
    private static final int MAX_OUTPUT_LENGTH = 64 * 1024;

    /**
     * 用户代码最大字符数
     */
    private static final int MAX_CODE_LENGTH = 256 * 1024;

    /**
     * 从代码中提取主类名（public class Xxx），未匹配到时默认 Main
     */
    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile("public\\s+class\\s+([A-Za-z_]\\w*)");

    @Value("${codesandbox.auth-secret:secretKey}")
    private String authSecret;

    /**
     * 单个用例运行超时（毫秒）
     */
    @Value("${codesandbox.run-timeout-ms:5000}")
    private long runTimeoutMs;

    /**
     * 编译超时（毫秒）
     */
    @Value("${codesandbox.compile-timeout-ms:15000}")
    private long compileTimeoutMs;

    @Override
    public void checkAuth(String auth) {
        if (StrUtil.isBlank(auth) || !authSecret.equals(auth)) {
            throw new SecurityException("无权限访问代码沙箱");
        }
    }

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        String code = executeCodeRequest.getCode();
        List<String> inputList = executeCodeRequest.getInputList();
        if (StrUtil.isBlank(code) || code.length() > MAX_CODE_LENGTH) {
            return fail("代码为空或超过长度限制");
        }
        if (inputList == null) {
            inputList = new ArrayList<>();
        }
        Path workDir = null;
        try {
            // 1. 提取类名并写入临时目录
            workDir = Files.createTempDirectory("codeoj-sandbox-");
            String className = resolveClassName(code);
            Path sourceFile = workDir.resolve(className + ".java");
            Files.write(sourceFile, code.getBytes(StandardCharsets.UTF_8));

            // 2. 编译
            long compileStart = System.currentTimeMillis();
            Process compile = new ProcessBuilder("javac", "-encoding", "UTF-8", sourceFile.toAbsolutePath().toString())
                    .directory(workDir.toFile())
                    .start();
            String compileError = readOutput(compile.getErrorStream());
            boolean compileFinished = compile.waitFor(compileTimeoutMs, TimeUnit.MILLISECONDS);
            if (!compileFinished) {
                compile.destroyForcibly();
                return fail("编译超时");
            }
            if (compile.exitValue() != 0) {
                return fail("编译错误：" + StrUtil.sub(compileError, 0, 500));
            }
            log.info("沙箱编译完成，className = {}，耗时 {} ms", className, System.currentTimeMillis() - compileStart);

            // 3. 逐用例运行
            List<String> outputList = new ArrayList<>();
            long maxCostMs = 0;
            for (String input : inputList) {
                long runStart = System.currentTimeMillis();
                Process run = new ProcessBuilder("java", "-Dfile.encoding=UTF-8", "-XX:+UseSerialGC",
                                "-cp", workDir.toAbsolutePath().toString(), className)
                        .directory(workDir.toFile())
                        .start();
                // 喂入输入用例
                try (BufferedWriter writer = new BufferedWriter(
                        new java.io.OutputStreamWriter(run.getOutputStream(), StandardCharsets.UTF_8))) {
                    if (StrUtil.isNotBlank(input)) {
                        writer.write(input);
                    }
                    writer.flush();
                }
                String output = readOutput(run.getInputStream());
                boolean runFinished = run.waitFor(runTimeoutMs, TimeUnit.MILLISECONDS);
                if (!runFinished) {
                    run.destroyForcibly();
                    return fail("运行超时");
                }
                long cost = System.currentTimeMillis() - runStart;
                maxCostMs = Math.max(maxCostMs, cost);
                if (run.exitValue() != 0) {
                    String error = readOutput(run.getErrorStream());
                    return fail("运行错误（退出码 " + run.exitValue() + "）：" + StrUtil.sub(error, 0, 500));
                }
                outputList.add(normalizeOutput(output));
            }

            // 4. 组装结果
            JudgeInfo judgeInfo = new JudgeInfo();
            judgeInfo.setTime(maxCostMs);
            judgeInfo.setMemory(0L);
            return ExecuteCodeResponse.builder()
                    .outputList(outputList)
                    .message("ok")
                    .status(STATUS_SUCCESS)
                    .judgeInfo(judgeInfo)
                    .build();
        } catch (IOException e) {
            log.error("沙箱执行 IO 异常", e);
            return fail("沙箱执行异常：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail("沙箱执行被中断");
        } catch (Exception e) {
            log.error("沙箱执行异常", e);
            return fail("沙箱执行异常：" + e.getMessage());
        } finally {
            cleanup(workDir);
        }
    }

    /**
     * 从代码中提取主类名
     */
    private String resolveClassName(String code) {
        Matcher matcher = CLASS_NAME_PATTERN.matcher(code);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "Main";
    }

    /**
     * 读取流内容，超过上限即截断
     */
    private String readOutput(java.io.InputStream stream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[1024];
            int n;
            while ((n = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, n);
                if (sb.length() > MAX_OUTPUT_LENGTH) {
                    return sb.substring(0, MAX_OUTPUT_LENGTH);
                }
            }
            return sb.toString();
        }
    }

    /**
     * 归一化输出：去除首尾空白与行尾 \r，保证判题比对不受平台换行符影响
     */
    private String normalizeOutput(String output) {
        if (StrUtil.isEmpty(output)) {
            return "";
        }
        return output.replace("\r\n", "\n").trim();
    }

    /**
     * 递归删除临时目录
     */
    private void cleanup(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException e) {
            log.warn("清理沙箱临时目录失败：{}", dir, e);
        }
    }

    private ExecuteCodeResponse fail(String message) {
        return ExecuteCodeResponse.builder()
                .outputList(new ArrayList<>())
                .message(message)
                .status(STATUS_FAILED)
                .build();
    }
}
