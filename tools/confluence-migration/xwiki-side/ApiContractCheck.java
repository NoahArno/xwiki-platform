// 目的：把 ImportSchedulerJob.groovy 里用到的每一处 XWiki API 调用用静态类型重写一遍，
// 让 javac 验证方法签名。
//
// 为什么需要它：Groovy 是动态类型，编译 Groovy 脚本只能查出语法与 import 错误，
// 查不出方法名/参数写错。B 侧脚本无法在本地真实 XWiki 上执行（OA SSO + superadmin 禁用），
// 所以用这种方式把"签名对不对"这件事变成可验证的。
//
// 用法见同目录 verify.sh。

import com.xpn.xwiki.web.Utils;
import org.xwiki.environment.Environment;
import org.xwiki.filter.internal.job.FilterStreamConverterJob;
import org.xwiki.filter.job.FilterStreamConverterJobRequest;
import org.xwiki.filter.type.FilterStreamType;
import org.xwiki.job.Job;
import org.xwiki.job.JobExecutor;
import org.xwiki.job.event.status.JobStatus;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class ApiContractCheck
{
    public static void main(String[] args)
    {
        try {
            check();
            System.out.println("OK");
        } catch (Exception e) {
            System.err.println("FAILED: " + e);
            System.exit(1);
        }
    }

    public static void check() throws Exception
    {
        // 1. 永久目录 -> migration 子目录
        Environment environment = Utils.getComponent(Environment.class);
        File migrationRoot = new File(environment.getPermanentDirectory(), "migration");

        // 2. Job 执行器
        JobExecutor jobExecutor = Utils.getComponent(JobExecutor.class);

        // 3. 过滤器类型反序列化
        FilterStreamType inputType = FilterStreamType.unserialize("confluence+xml");
        FilterStreamType outputType = FilterStreamType.unserialize("xwiki+instance");

        // 4. 输入属性（与脚本中一一对应）
        Map<String, Object> inputProperties = new HashMap<>();
        inputProperties.put("source", "file:" + migrationRoot.getAbsolutePath());
        inputProperties.put("contentsEnabled", Boolean.TRUE);
        inputProperties.put("attachmentsEnabled", Boolean.TRUE);
        inputProperties.put("historyEnabled", Boolean.FALSE);
        inputProperties.put("usersEnabled", Boolean.FALSE);
        inputProperties.put("groupsEnabled", Boolean.FALSE);
        inputProperties.put("rightsEnabled", Boolean.FALSE);
        inputProperties.put("archivedDocumentsEnabled", Boolean.FALSE);
        inputProperties.put("archivedSpacesEnabled", Boolean.FALSE);

        // 5. 构造作业请求
        FilterStreamConverterJobRequest request = new FilterStreamConverterJobRequest(
            inputType, inputProperties, outputType, new HashMap<String, Object>());

        // 6. 执行并同步等待
        Job job = jobExecutor.execute(FilterStreamConverterJob.JOBTYPE, request);
        job.join();

        // 7. 判定成功
        JobStatus status = job.getStatus();
        boolean succeeded = status.getState() == JobStatus.State.FINISHED && status.getError() == null;
        if (!succeeded) {
            throw new IllegalStateException("导入未成功");
        }
    }
}
