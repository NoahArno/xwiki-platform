/**
 * Confluence 迁移包自动导入 —— XWiki Scheduler (Groovy) 作业脚本。
 *
 * 部署位置：A(Confluence) 生成包 → rsync 到 B 的 <permanentDir>/migration/ → 本脚本定时扫描并导入。
 *
 * 为什么用 Scheduler + Groovy 而不是 HTTP 接口：
 *   触发导入需要 Right.PROGRAM，而 B 的 XWiki 走的是自定义 OA SSO。
 *   让 A 去 HTTP 触发 B 需要额外的服务账号与 SSO 打通；反过来"往目录丢文件、
 *   XWiki 自己扫"不需要任何外部认证，任一方重启都不会丢状态。
 *
 * 为什么是 Groovy 而不是 Velocity：
 *   Groovy 有完整 Java 访问权限，读清单、列目录、写台账都是普通 File IO；
 *   在 Velocity 里做这些需要靠组件查找绕路，既笨拙又难排错。
 *
 * 安装方式见同目录 README.md。
 */

import com.xpn.xwiki.web.Utils
import org.xwiki.environment.Environment
import org.xwiki.filter.internal.job.FilterStreamConverterJob
import org.xwiki.filter.job.FilterStreamConverterJobRequest
import org.xwiki.filter.type.FilterStreamType
import org.xwiki.job.Job
import org.xwiki.job.JobExecutor
import org.xwiki.job.event.status.JobStatus

// ============================ 配置 ============================
/** 迁移包所在目录，相对 XWiki 永久目录。 */
final String MIGRATION_DIR = 'migration'
/** 台账文件名，由本脚本维护（A 侧的 manifest.csv 只读不改）。 */
final String LEDGER_FILE = 'ledger.csv'
/** 防止定时器重叠执行的上锁文件。 */
final String LOCK_FILE = '.import.lock'
/** 锁超过该分钟数视为陈旧（上次执行被强杀），可被抢占。 */
final long LOCK_STALE_MINUTES = 180
/** 每次执行最多导入几个包。设为 1 可以让失败的包不阻塞后续包。 */
final int MAX_PACKAGES_PER_RUN = 1
/** 是否导入附件。 */
final boolean INCLUDE_ATTACHMENTS = true
/** 是否导入历史版本。按当前迁移范围不需要。 */
final boolean INCLUDE_HISTORY = false
// =============================================================

def say = { Object message -> println "[confluence-import] ${message}" }
def now = { new java.text.SimpleDateFormat('yyyy-MM-dd HH:mm:ss').format(new Date()) }

// ---- 1. 定位目录 ----
def migrationRoot = new File(Utils.getComponent(Environment.class).getPermanentDirectory(), MIGRATION_DIR)
if (!migrationRoot.isDirectory()) {
    say "迁移目录不存在，跳过: ${migrationRoot.absolutePath}"
    return
}
def ledgerFile = new File(migrationRoot, LEDGER_FILE)

// ---- 2. 上锁，避免定时器重叠 ----
def lockFile = new File(migrationRoot, LOCK_FILE)
if (lockFile.isFile()) {
    long ageMinutes = (System.currentTimeMillis() - lockFile.lastModified()) / 60000L
    if (ageMinutes < LOCK_STALE_MINUTES) {
        say "上一次导入仍在进行中（锁 ${ageMinutes} 分钟前建立），本次跳过"
        return
    }
    say "锁已陈旧（${ageMinutes} 分钟），视为上次异常退出，继续执行"
}
lockFile.text = now()
lockFile.setLastModified(System.currentTimeMillis())

try {
    // ---- 3. 读台账 ----
    // 格式：<空间Key>\t<状态>\t<完成时间>\t<说明>
    def done = [] as Set
    def failed = [:] as Map
    if (ledgerFile.isFile()) {
        ledgerFile.eachLine('UTF-8') { String line ->
            def parts = line.split('\t', -1)
            if (parts.length >= 2) {
                if (parts[1] == 'DONE') {
                    done << parts[0]
                } else if (parts[1] == 'FAILED') {
                    failed[parts[0]] = parts.length >= 4 ? parts[3] : ''
                }
            }
        }
    }
    say "台账：已完成 ${done.size()} 个，失败 ${failed.size()} 个"

    // ---- 4. 找出待导入的包 ----
    // 一个包 = 一个含 entities.xml 的目录，目录名即 Confluence 空间 Key
    def pending = (migrationRoot.listFiles() ?: new File[0])
        .findAll { it.isDirectory() && new File(it, 'entities.xml').isFile() }
        .findAll { !done.contains(it.name) }
        .sort { it.name }

    if (pending.isEmpty()) {
        say '没有待导入的包'
        return
    }
    say "待导入 ${pending.size()} 个，本次处理最多 ${MAX_PACKAGES_PER_RUN} 个"

    // ---- 5. 逐个导入 ----
    def jobExecutor = Utils.getComponent(JobExecutor.class)
    def inputType = FilterStreamType.unserialize('confluence+xml')
    def outputType = FilterStreamType.unserialize('xwiki+instance')
    int processed = 0

    for (File pkg : pending) {
        if (processed >= MAX_PACKAGES_PER_RUN) {
            break
        }
        processed++
        String spaceKey = pkg.name
        say "开始导入空间 [${spaceKey}]，包目录 ${pkg.absolutePath}"

        String state
        String detail
        try {
            def inputProperties = new HashMap<String, Object>()
            // source 支持 file: 前缀，且对"目录"是就地读取，不做解压
            inputProperties.put('source', "file:${pkg.absolutePath}")
            inputProperties.put('contentsEnabled', true)
            inputProperties.put('attachmentsEnabled', INCLUDE_ATTACHMENTS)
            inputProperties.put('historyEnabled', INCLUDE_HISTORY)
            // 迁移范围只含数据：用户/组/权限一律不导入
            inputProperties.put('usersEnabled', false)
            inputProperties.put('groupsEnabled', false)
            inputProperties.put('rightsEnabled', false)
            inputProperties.put('archivedDocumentsEnabled', false)
            inputProperties.put('archivedSpacesEnabled', false)

            def request = new FilterStreamConverterJobRequest(
                inputType, inputProperties, outputType, new HashMap<String, Object>())
            Job job = jobExecutor.execute(FilterStreamConverterJob.JOBTYPE, request)
            // 同步等待：Scheduler 跑在后台线程，阻塞是安全的，
            // 而且省掉了"异步 + 轮询状态"的整套状态机
            job.join()

            JobStatus status = job.getStatus()
            if (status.getState() == JobStatus.State.FINISHED && status.getError() == null) {
                state = 'DONE'
                detail = ''
                say "空间 [${spaceKey}] 导入完成"
            } else {
                state = 'FAILED'
                detail = status.getError() == null ? "状态 ${status.getState()}" : status.getError().toString()
                say "空间 [${spaceKey}] 导入失败: ${detail}"
            }
        } catch (Throwable t) {
            state = 'FAILED'
            detail = t.toString()
            say "空间 [${spaceKey}] 导入异常: ${detail}"
        }

        // ---- 6. 立即落台账 ----
        // 每个包导完就写：中途被强杀时，已完成的部分不会白跑
        def safeDetail = (detail ?: '').replaceAll('[\\t\\r\\n]+', ' ')
        ledgerFile.append("${spaceKey}\t${state}\t${now()}\t${safeDetail}\n", 'UTF-8')
    }

    say "本次执行结束，处理了 ${processed} 个包"
} finally {
    // ---- 7. 释放锁 ----
    if (lockFile.isFile() && !lockFile.delete()) {
        say "警告：锁文件删除失败 ${lockFile.absolutePath}，下次执行可能被误判为进行中"
    }
}
