import com.codeoj.codeojbackendcodesandbox.service.impl.JavaNativeCodeSandbox;
import com.codeoj.codeojbackendjudgeservice.judge.SandboxResultMapper;
import com.codeoj.codeojbackendjudgeservice.judge.strategy.JavaLanguageJudgeStrategy;
import com.codeoj.codeojbackendjudgeservice.judge.strategy.JudgeContext;
import com.codeoj.codeojbackendmodel.model.codesandbox.*;
import com.codeoj.codeojbackendmodel.model.dto.question.JudgeCase;
import com.codeoj.codeojbackendmodel.model.entity.Question;
import com.codeoj.codeojbackendmodel.model.enums.JudgeVerdict;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import cn.hutool.json.JSONUtil;

/** Trusted samples through the actual executor and judge strategy, without infrastructure. */
public class LocalJudgeDemo {
    public static void main(String[] args) {
        JavaNativeCodeSandbox sandbox = new JavaNativeCodeSandbox(1);
        check(sandbox, "AC", "java.util.Scanner s=new java.util.Scanner(System.in);"
                + " System.out.println(s.nextInt()+s.nextInt()); Thread.sleep(100);", "1 2", "3", JudgeVerdict.AC);
        check(sandbox, "WA", "System.out.println(4); Thread.sleep(100);", "1 2", "3", JudgeVerdict.WA);
        check(sandbox, "CE", "this is invalid java;", "", "", JudgeVerdict.CE);
        check(sandbox, "TLE", "while(true) {}", "", "", JudgeVerdict.TLE);
        check(sandbox, "OUTPUT_LIMIT", "while(true){System.err.print(\"0123456789abcdefghijklmnopqrstuvwxyz\");}",
                "", "", JudgeVerdict.OUTPUT_LIMIT);
    }

    private static void check(JavaNativeCodeSandbox sandbox, String name, String body, String input,
                              String expectedOutput, JudgeVerdict expectedVerdict) {
        ExecuteCodeRequest request = ExecuteCodeRequest.builder().language("java")
                .code("public class Main { public static void main(String[] args) throws Exception {" + body + "}}")
                .inputList(Collections.singletonList(input)).runTimeLimitMs(1000L).memoryLimitKiB(131072L).build();
        ExecuteCodeResponse response = sandbox.executeCode(request);
        JudgeInfo verdict = SandboxResultMapper.userVerdict(response);
        if (verdict == null) {
            Question question = new Question();
            question.setJudgeConfig("{\"timeLimit\":1000,\"memoryLimit\":131072}");
            JudgeCase testCase = new JudgeCase();
            testCase.setInput(input);
            testCase.setOutput(expectedOutput);
            JudgeContext context = new JudgeContext();
            context.setQuestion(question);
            context.setJudgeInfo(response.getJudgeInfo());
            context.setInputList(request.getInputList());
            context.setOutputList(response.getOutputList());
            context.setJudgeCaseList(Collections.singletonList(testCase));
            verdict = new JavaLanguageJudgeStrategy().doJudge(context);
        }
        if (verdict.getVerdict() != expectedVerdict) {
            throw new IllegalStateException(name + " expected " + expectedVerdict + " got " + verdict.getVerdict());
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("case", name);
        record.put("verdict", verdict.getVerdict().name());
        record.put("wallMs", verdict.getTime());
        record.put("memoryKiB", verdict.getMemory());
        record.put("memoryMeasurement", verdict.getMemoryMeasurement());
        System.out.println(JSONUtil.toJsonStr(record));
    }
}
