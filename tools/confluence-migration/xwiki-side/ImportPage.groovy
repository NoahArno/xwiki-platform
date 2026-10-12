{{groovy}}
/*
 * Confluence 迁移包 —— 手动导入页（XWiki 侧）。
 *
 * 部署：新建页面（建议 Migration.Import，语法 xwiki/2.1），把本文件全文粘进页面内容，
 * 用有编程权限的用户保存。之后导入就是「打开这个页面 -> 点某一行的导入 -> 在 Filter 页面点 Convert」。
 *
 * 这里刻意不做任何自动/定时的事：
 *   - 没有 cron，没有后台轮询，没有锁文件；
 *   - 不在页面加载时偷偷执行导入（只有你在 Filter 页面点 Convert 才会真正写数据）；
 *   - 不维护 ledger：包是否已导入，直接和 A 侧写定的 manifest.csv 对账，对不上就是没导完。
 *
 * 页面的三段职责：
 *   1. 列出 <迁移目录> 下所有含 entities.xml 的包；
 *   2. 读 A 侧只读的 manifest.csv，显示「应该有多少页面/附件」；
 *   3. 查 XWiki 现状，显示「实际有多少页面/附件」，并给出预填好的 Filter 转换链接。
 *
 * 为什么走 Filter Streams Converter 而不是自己调 API：
 *   转换页是 XWiki 自带的标准入口，导入选项在页面上全部可见可改，
 *   失败时也有现成的作业状态与日志；本页只负责「把包找出来、把参数填好」。
 *
 * 注意：脚本宏的输出会被当成 wiki 语法再解析一次，所以生成的 HTML 必须整体包在 html 宏里，
 * 否则整段 HTML 会被转义成可见文本。
 *
 * 前置：XWiki 上已装 Confluence 导入器（confluence+xml）与 Filter Streams Converter 应用。
 */
import com.xpn.xwiki.web.Utils
import org.xwiki.environment.Environment

// ============================ 配置 ============================
/** 迁移包目录。相对路径按 XWiki 的 permanentDirectory 解析；也可用 ?dir=/abs/path 临时覆盖。 */
final String DEFAULT_MIGRATION_DIR = 'migration'

/**
 * 导入时显式关掉的开关。
 * 本工具的导出范围只有内容与附件（见 README「当前未覆盖的范围」），
 * 所以用户/组/权限/历史一律不开；不开就不会去猜、也不会写坏现有数据。
 * 其余选项（contentsEnabled / attachmentsEnabled / 名称校验 / Confluence 详情对象等）保持导入器默认值。
 */
final Map<String, String> IMPORT_OPTIONS = [
    historyEnabled          : 'false',
    usersEnabled            : 'false',
    groupsEnabled           : 'false',
    rightsEnabled           : 'false',
    archivedDocumentsEnabled: 'false',
    archivedSpacesEnabled   : 'false',
]
// =============================================================

def esc = { Object s ->
    (s ?: '').toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')
}
def url = { Object s -> java.net.URLEncoder.encode(s.toString(), 'UTF-8') }

def out = new StringBuilder()
def emit = { Object html -> println html }

// 这个页面会列目录、读清单、按 ?dir= 读任意路径，所以只让有编程权限的人看。
// 注意：脚本宏里的 xcontext.getXWiki() 是内部的 com.xpn.xwiki.XWiki（没有 hasProgrammingRights），
// 带这个方法的 api 包装对象是脚本里的 xwiki 变量。
if (!xwiki.hasProgrammingRights()) {
    emit "{{html}}<p style='color:#b60'>本页只对拥有编程权限（Programming Right）的用户开放。</p>{{/html}}"
    return
}

def permanentDir = Utils.getComponent(Environment.class).getPermanentDirectory()
def dirParam = request.getParameter('dir')
def root = dirParam ? new File(dirParam) : new File(permanentDir, DEFAULT_MIGRATION_DIR)

out.append("<style>.ci-table td,.ci-table th{padding:4px 10px;border:1px solid #ccc;text-align:left}")
out.append(".ci-ok{color:#080}.ci-warn{color:#b60}.ci-todo{color:#666}</style>")

if (!root.isDirectory()) {
    out.append("<p class='ci-warn'>迁移目录不存在：<code>${esc(root.absolutePath)}</code>。")
    out.append("先把 A 侧的包拷到这里，或用 <code>?dir=/绝对路径</code> 指向别处。</p>")
    emit "{{html}}${out}{{/html}}"
    return
}

// ---- 1. A 侧只读清单：应该导入什么、各预期多少 ----
def manifest = [:]
def manifestFile = new File(root, 'manifest.csv')
if (manifestFile.isFile()) {
    manifestFile.eachLine('UTF-8') { String line ->
        if (line.startsWith('spaceKey,') || line.trim().isEmpty()) {
            return
        }
        def parts = line.split(',', -1)
        if (parts.length >= 6) {
            try {
                manifest[parts[0].trim()] = [
                    pages      : parts[2].trim() as int,
                    attachments: parts[4].trim() as int,
                    bytes      : parts[5].trim() as long,
                ]
            } catch (NumberFormatException ignored) {
                // 清单里这一行列数不对就跳过，不要因为一行坏数据让整页打不开
            }
        }
    }
}

// ---- 2. B 侧现状：这个空间现在有多少页面/附件 ----
// 导入器把一个 Confluence 页面落成一个嵌套空间 <SpaceKey>.<PageTitle>/WebHome，
// 所以「页面数」= 该前缀下所有 *.WebHome 文档数（自动生成的 <SpaceKey>.WebPreferences 不算页面）。
def currentState = { String spaceKey ->
    def query = services.query.xwql(
        'select doc.fullName from Document doc where doc.space = :key or doc.space like :nested')
    query.bindValue('key', spaceKey)
    query.bindValue('nested', spaceKey + '.%')

    int pages = 0
    int attachments = 0
    long bytes = 0L
    query.execute().each { Object fullName ->
        if (fullName.toString().endsWith('.WebHome')) {
            pages++
        }
        def doc = xcontext.getXWiki().getDocument(fullName.toString(), xcontext.getContext())
        if (doc != null) {
            def list = doc.getAttachmentList()
            attachments += list.size()
            list.each { bytes += it.getLongSize() }
        }
    }
    [pages: pages, attachments: attachments, bytes: bytes]
}

// ---- 3. 拼出预填好的 Filter Streams Converter 链接 ----
def converterLink = { File pkg ->
    def params = [input: 'confluence+xml', output: 'xwiki+instance']
    params['filter_input_properties_descriptor_source'] = "file:${pkg.absolutePath}"
    IMPORT_OPTIONS.each { key, value ->
        params["filter_input_properties_descriptor_${key}"] = value
    }
    def queryString = params.collect { k, v -> "${url(k)}=${url(v)}" }.join('&amp;')
    "${request.getContextPath()}/bin/view/Filter/?${queryString}"
}

// ---- 4. 渲染 ----
def packages = (root.listFiles() ?: new File[0])
    .findAll { it.isDirectory() && new File(it, 'entities.xml').isFile() }
    .sort { it.name }

if (packages.isEmpty()) {
    out.append("<p class='ci-warn'>${esc(root.absolutePath)} 下没有找到任何包（缺少 <code>entities.xml</code>）。</p>")
    emit "{{html}}${out}{{/html}}"
    return
}

out.append("<p>迁移目录：<code>${esc(root.absolutePath)}</code>；共 <strong>${packages.size()}</strong> 个包。")
out.append("「导入」会打开已经填好参数的 Filter Streams Converter，")
out.append("<strong>还需你在那里点一次 Convert</strong>。</p>")

out.append("<table class='ci-table'><tr>")
out.append("<th>空间</th><th>期望（manifest.csv）</th><th>现状（XWiki）</th><th>状态</th><th>操作</th></tr>")

int done = 0
packages.each { File pkg ->
    String key = pkg.name
    def expected = manifest[key]
    def actual = currentState(key)
    boolean matches = expected != null &&
        expected.pages == actual.pages &&
        expected.attachments == actual.attachments

    String status
    if (matches) {
        done++
        status = "<span class='ci-ok'>✓ 与清单一致</span>"
    } else if (actual.pages == 0 && actual.attachments == 0) {
        status = "<span class='ci-todo'>未导入</span>"
    } else {
        status = "<span class='ci-warn'>⚠ 与清单不一致</span>"
    }

    String expectedText = expected == null
        ? "<span class='ci-warn'>清单里没有这个空间</span>"
        : "${expected.pages} 页 / ${expected.attachments} 附件 / ${expected.bytes} 字节".toString()

    out.append("<tr>")
    out.append("<td><code>${esc(key)}</code></td>")
    out.append("<td>${expectedText}</td>")
    out.append("<td>${actual.pages} 页 / ${actual.attachments} 附件 / ${actual.bytes} 字节</td>")
    out.append("<td>${status}</td>")
    out.append("<td><a class='button' href='${converterLink(pkg)}'>导入 →</a></td>")
    out.append("</tr>")
}
out.append("</table>")

out.append("<p>对账进度：<strong>${done}/${packages.size()}</strong>。")
out.append("数字对不上时先看 Filter 页面的作业状态与 XWiki 日志，再决定是否重导")
out.append("（导入器默认会清掉上次导入产生的页面，可重复执行）。</p>")

emit "{{html}}${out}{{/html}}"
{{/groovy}}
