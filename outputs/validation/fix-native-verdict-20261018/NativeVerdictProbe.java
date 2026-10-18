import com.codeoj.codeojbackendcodesandbox.service.impl.JavaNativeCodeSandbox;
import com.codeoj.codeojbackendmodel.model.codesandbox.*;
import cn.hutool.json.JSONUtil;
import java.util.*;
public class NativeVerdictProbe {
 public static void main(String[] a) {
  JavaNativeCodeSandbox sandbox=new JavaNativeCodeSandbox(1);
  String main="public class Main { public static void main(String[] args) throws Exception { System.out.println(3); Thread.sleep(100); }}";
  probe(sandbox,"plain-main",main,"EXECUTED");
  probe(sandbox,"comment-before-main","// public class Decoy\n"+main,"EXECUTED");
  probe(sandbox,"uncaught-user-exception","public class Main { public static void main(String[] args) { throw new IllegalArgumentException(); }}","RE");
 }
 static void probe(JavaNativeCodeSandbox s,String name,String code,String expected) {
  ExecuteCodeRequest r=ExecuteCodeRequest.builder().language("java").code(code).inputList(Collections.singletonList("")).runTimeLimitMs(1000L).memoryLimitKiB(262144L).build();
  ExecuteCodeResponse out=s.executeCode(r);
  Map<String,Object> result=new LinkedHashMap<>();result.put("case",name);result.put("expected",expected);result.put("actual",out.getOutcome());result.put("errorCode",out.getErrorCode());result.put("output",out.getOutputList());
  System.out.println("PROBE "+JSONUtil.toJsonStr(result));
 }
}
