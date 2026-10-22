package com.codeoj.codeojbackendcodesandbox.service.impl;

import cn.hutool.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Trusted CLI client. Arguments are vectors; no source/input becomes a shell command. */
final class DockerIsolationEngine implements IsolationEngine {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DockerIsolationEngine.class);
    private final String binary, endpoint, image, pool;
    private final Path root;
    private final BoundedProcessRunner runner = new BoundedProcessRunner();
    DockerIsolationEngine(String binary, String endpoint, String image, String pool, Path root) throws IOException {
        if (!image.matches("(?:sha256:|[a-zA-Z0-9./:_-]+@sha256:)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("PINNED_ISOLATION_IMAGE_REQUIRED");
        }
        this.binary = binary; this.endpoint = endpoint; this.image = image; this.pool = pool;
        this.root = root;
        Files.createDirectories(root.resolve("docker-client"));
    }
    private List<String> command(String... args) {
        List<String> command = new ArrayList<>(Arrays.asList(binary, "--config", root.resolve("docker-client").toString(), "--host", endpoint));
        command.addAll(Arrays.asList(args)); return command;
    }
    private BoundedProcessRunner.Result invoke(List<String> command, String input, long timeout, int output)
            throws IOException, InterruptedException {
        return runner.run(command, Paths.get(System.getProperty("java.io.tmpdir")), input, timeout, output);
    }
    private String control(String... args) throws IOException, InterruptedException {
        BoundedProcessRunner.Result result = invoke(command(args), "", 8000, 131072);
        if (result.end != BoundedProcessRunner.End.EXITED || result.exitCode != 0) {
            LOG.warn("Isolation engine {} failed: end={}, exit={}, diagnostic={}", args[0], result.end,
                    result.exitCode, result.stderr.substring(0, Math.min(240, result.stderr.length())).replace('\n', ' '));
            throw new IOException("ISOLATION_ENGINE_COMMAND_FAILED");
        }
        return result.stdout.trim();
    }
    void verify() throws IOException, InterruptedException {
        JSONObject info = JSONUtil.parseObj(control("info", "--format", "{{json .}}"));
        if (!"linux".equals(info.getStr("OSType")) || !"2".equals(info.getStr("CgroupVersion"))) {
            throw new IOException("LINUX_CGROUP_V2_REQUIRED");
        }
        for (String flag : Arrays.asList("MemoryLimit", "SwapLimit", "PidsLimit", "CpuCfsQuota")) {
            if (!Boolean.TRUE.equals(info.getBool(flag))) { throw new IOException("CGROUP_CONTROLLER_UNAVAILABLE"); }
        }
        if (!String.valueOf(info.get("SecurityOptions")).contains("seccomp")) {
            throw new IOException("SECCOMP_REQUIRED");
        }
        String protocol = control("image", "inspect", image, "--format", "{{index .Config.Labels \"codeoj.isolation.protocol\"}}");
        if (!"1".equals(protocol)) { throw new IOException("ISOLATION_IMAGE_PROTOCOL_MISMATCH"); }
    }
    String name(String token, String stage) { return "codeoj-" + pool + "-" + token.replace("-", "") + "-" + stage; }
    private List<String> labels(String token) {
        return Arrays.asList("--label", "codeoj.pool=" + pool, "--label", "codeoj.token=" + token);
    }
    String volume(String token, String stage) throws IOException, InterruptedException {
        String name = name(token, stage);
        List<String> args = new ArrayList<>(Arrays.asList("volume", "create")); args.addAll(labels(token)); args.add(name);
        control(args.toArray(new String[0])); return name;
    }
    String create(String token, String stage, String artifact, boolean readOnlyArtifacts, String receipt, String sourceVolume,
                  long memoryKiB, int pids, String cpus, long deadline, long stageBudget, String... program)
            throws IOException, InterruptedException {
        String name = name(token, stage);
        List<String> args = new ArrayList<>(Arrays.asList("create", "--name", name, "--init", "--interactive",
                "--restart", "no", "--network", "none", "--read-only", "--cap-drop", "ALL",
                "--cap-add", "SETUID", "--cap-add", "SETGID", "--cap-add", "KILL",
                "--security-opt", "no-new-privileges:true", "--pids-limit", Integer.toString(pids),
                "--memory", (memoryKiB * 1024) + "", "--memory-swap", (memoryKiB * 1024) + "",
                "--cpus", cpus, "--ulimit", "nofile=64:64", "--ulimit", "fsize=16777216:16777216",
                "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=16777216,mode=1777", "--log-driver", "none",
                "--mount", "type=volume,source=" + artifact + ",target=/work" + (readOnlyArtifacts ? ",readonly" : ""),
                "--mount", "type=volume,source=" + receipt + ",target=/receipt"));
        if (sourceVolume != null) { args.add("--mount"); args.add("type=volume,source=" + sourceVolume + ",target=/source"); }
        args.addAll(labels(token)); args.add(image); args.add(Long.toString(deadline)); args.add(Long.toString(stageBudget)); args.addAll(Arrays.asList(program));
        control(args.toArray(new String[0])); return name;
    }
    void upload(String container, Path source) throws IOException, InterruptedException {
        control("cp", source.toString(), container + ":/source/Submission.java");
    }
    void copy(String container, String source, Path destination) throws IOException, InterruptedException {
        control("cp", container + ":" + source, destination.toString());
    }
    BoundedProcessRunner.Result start(String container, String input, long timeout, int output)
            throws IOException, InterruptedException {
        return invoke(command("start", "--attach", "--interactive", container), input, timeout, output);
    }
    JSONObject inspect(String container) throws IOException, InterruptedException {
        return JSONUtil.parseObj(control("inspect", container, "--format", "{{json .State}}"));
    }
    @Override public void removeExecution(String token) throws IOException, InterruptedException {
        removeObjects(token, false);
        removeObjects(token, true);
        Path work = root.resolve("work").resolve(token);
        if (Files.exists(work)) {
            try (java.util.stream.Stream<Path> paths = Files.walk(work)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) { Files.delete(path); }
            }
        }
    }
    private void removeObjects(String token, boolean volumes) throws IOException, InterruptedException {
        String[] args = volumes ? new String[]{"volume", "ls", "--quiet", "--filter", "label=codeoj.pool=" + pool,
                "--filter", "label=codeoj.token=" + token} : new String[]{"ps", "--all", "--quiet", "--filter",
                "label=codeoj.pool=" + pool, "--filter", "label=codeoj.token=" + token};
        String objects = control(args);
        for (String id : objects.split("\\s+")) {
            if (id.isEmpty()) { continue; }
            if (volumes) { control("volume", "rm", id); }
            else { control("rm", "--force", id); }
        }
        if (!control(args).isEmpty()) { throw new IOException("ISOLATION_RESOURCES_REMAIN"); }
    }
}
