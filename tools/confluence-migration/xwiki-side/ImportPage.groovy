{{groovy}}
/*
 * Confluence 迁移包 —— 手动导入 + 空间注册页（XWiki 侧）。
 *
 * 部署：新建页面（建议 Migration.Import，语法 xwiki/2.1），把本文件全文粘进页面内容，
 * 用有编程权限的用户保存。
 *
 * 它把迁移在 B 侧的两件事都包了：
 *   1. 导入：列出包、显示 manifest 期望值 vs XWiki 现状，给出预填好参数的 Filter 转换链接；
 *   2. 空间注册：导入对账通过后，在 <SpaceKey>.WebHome 上挂 SpaceCode.SpaceClass 对象，
 *      让它成为你们自定义的「空间」（等价于 Spaces.CreateSpace 做的事，只是对象已存在的空间它不让建）。
 *
 * 为什么空间注册放在这里而不是改 xwiki-platform：
 *   「是不是空间」的判定只有一条——顶层空间的 WebHome 上有没有 SpaceCode.SpaceClass 对象
 *   （SpaceDirectoryLivetableResults 与 SpaceManager 用的是同一口径）。
 *   所以注册只是给已有页面加一个对象，属于数据操作，用脚本 API 就能完成，
 *   不需要动 SpaceManager / CreateSpace 任何一个应用侧文件。
 *
 * 刻意不做的事：
 *   - 没有 cron、没有后台轮询、没有锁文件、不维护 ledger；
 *   - 页面加载不写任何数据；只有你在 Filter 页面点 Convert、或点本页的「注册为空间」才会写；
 *   - 只有「现状与 manifest 完全一致」的包才允许注册，避免把没导完的空间标成已完成。
 *
 * 注意：脚本宏的输出会被当成 wiki 语法再解析一次，所以生成的 HTML 必须整体包在 html 宏里。
 *
 * 前置：XWiki 上已装 Confluence 导入器（confluence+xml）与 Filter Streams Converter 应用。
 */
import com.xpn.xwiki.web.Utils
import org.xwiki.environment.Environment

// ============================ 配置 ============================
/** 迁移包目录。相对路径按 XWiki 的 permanentDirectory 解析；也可用 ?dir=/abs/path 临时覆盖。 */
final String DEFAULT_MIGRATION_DIR = 'migration'

/** 你们自定义的「空间」标记类。 */
final String SPACE_CLASS = 'SpaceCode.SpaceClass'

/**
 * 导入器放博客的空间后缀（它自己的 {@code blogSpaceName} 默认是 {@code Blog}）。
 * Confluence 的博客会落到 {@code <SpaceKey>.Blog.<标题>}。
 */
final String BLOG_SPACE_SUFFIX = '.Blog'

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

// 这个页面会列目录、读清单、按 ?dir= 读任意路径，还能改数据，所以只让有编程权限的人看。
// 注意：脚本宏里的 xcontext.getXWiki() 是内部的 com.xpn.xwiki.XWiki（没有 hasProgrammingRights），
// 带这个方法的 api 包装对象是脚本里的 xwiki 变量。
if (!xwiki.hasProgrammingRights()) {
    emit "{{html}}<p style='color:#b60'>本页只对拥有编程权限（Programming Right）的用户开放。</p>{{/html}}"
    return
}

def permanentDir = Utils.getComponent(Environment.class).getPermanentDirectory()
def dirParam = request.getParameter('dir')
def root = dirParam ? new File(dirParam) : new File(permanentDir, DEFAULT_MIGRATION_DIR)
def dirQuery = dirParam ? "?dir=${url(dirParam)}" : ''

out.append("<style>.ci-table td,.ci-table th{padding:4px 10px;border:1px solid #ccc;text-align:left}")
out.append(".ci-ok{color:#080}.ci-warn{color:#b60}.ci-todo{color:#666}.ci-box{padding:8px 12px;margin:8px 0;border-radius:4px}")
out.append(".ci-box-ok{background:#eaf7ea}.ci-box-warn{background:#fdf3e3}</style>")

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
                    blogPosts  : parts[3].trim() as int,
                    attachments: parts[4].trim() as int,
                    bytes      : parts[5].trim() as long,
                ]
            } catch (NumberFormatException ignored) {
                // 清单里这一行列数不对就跳过，不要因为一行坏数据让整页打不开
            }
        }
    }
}

// ---- 2. B 侧现状：这个空间现在有多少页面/博客/附件 ----
// 导入器的落点规则（实测）：
//   Confluence 页面   -> <SpaceKey>.<PageTitle>/WebHome（嵌套空间）
//   Confluence 博客   -> <SpaceKey>.Blog.<博客标题>（一个普通文档，不是 WebHome）
//   另外它还会自建 <SpaceKey>.Blog.WebHome 与 <SpaceKey>.WebPreferences 这两个"壳"文档。
// 所以数页面时要排掉博客空间本身，博客单独按 <SpaceKey>.Blog 下的非 WebHome 文档数算。
def currentState = { String spaceKey ->
    String blogSpacePrefix = spaceKey + BLOG_SPACE_SUFFIX
    def query = services.query.xwql(
        'select doc.fullName from Document doc where doc.space = :key or doc.space like :nested')
    query.bindValue('key', spaceKey)
    query.bindValue('nested', spaceKey + '.%')

    int pages = 0
    int blogPosts = 0
    int attachments = 0
    long bytes = 0L
    query.execute().each { Object fullName ->
        String name = fullName.toString()
        int lastDot = name.lastIndexOf('.')
        String space = lastDot > 0 ? name.substring(0, lastDot) : ''
        String localName = lastDot > 0 ? name.substring(lastDot + 1) : name

        if (space == blogSpacePrefix || space.startsWith(blogSpacePrefix + '.')) {
            // 博客空间：壳文档（WebHome）不算，其余的都是博客
            if (localName != 'WebHome') {
                blogPosts++
            }
        } else if (localName == 'WebHome') {
            pages++
        }

        def doc = xcontext.getXWiki().getDocument(name, xcontext.getContext())
        if (doc != null) {
            def list = doc.getAttachmentList()
            attachments += list.size()
            list.each { bytes += it.getLongSize() }
        }
    }
    [pages: pages, blogPosts: blogPosts, attachments: attachments, bytes: bytes]
}

// ---- 3. 空间注册 ----
// 判定口径与 Spaces.SpaceDirectoryLivetableResults / SpaceManager 完全一致：
// 顶层空间的 WebHome 上有没有 SpaceCode.SpaceClass 对象。
def spaceHomeName = { String spaceKey -> "${spaceKey}.WebHome" }

def isRegisteredAsSpace = { String spaceKey ->
    def spaceDoc = xwiki.getDocument(spaceHomeName(spaceKey))
    spaceDoc != null && !spaceDoc.isNew() && spaceDoc.getObject(SPACE_CLASS) != null
}

/** @return [ok:boolean, message:String] */
def registerAsSpace = { String spaceKey ->
    String fullName = spaceHomeName(spaceKey)
    def spaceDoc = xwiki.getDocument(fullName)

    if (spaceDoc == null || spaceDoc.isNew()) {
        return [ok: false, message: "${fullName} 不存在，先把包装进去".toString()]
    }
    if (spaceDoc.getObject(SPACE_CLASS) != null) {
        return [ok: true, message: '已经是空间，无需重复注册'.toString()]
    }

    // 标题就是导入器从 Confluence 带过来的空间名（实测 ds.WebHome -> "Demonstration Space"）
    String spaceName = spaceDoc.getTitle()
    if (!spaceName) {
        spaceName = spaceKey
    }
    // Confluence 个人空间的 key 形如 ~username；本工具不迁用户，owner 只能记当前操作者
    String spaceType = spaceKey.startsWith('~') ? 'personal' : 'public'

    def spaceObj = spaceDoc.newObject(SPACE_CLASS)
    if (spaceObj == null) {
        return [ok: false, message: "无法创建 ${SPACE_CLASS} 对象（类文档缺失？）".toString()]
    }
    spaceObj.set('name', spaceName)
    spaceObj.set('key', spaceKey)
    spaceObj.set('type', spaceType)
    spaceObj.set('owner', xcontext.getUser())
    spaceDoc.save('Confluence 迁移：注册为空间')

    return [ok: true, message: "已注册为空间（name=${spaceName}, type=${spaceType}）".toString()]
}

// ---- 4. 处理「注册为空间」的提交 ----
def packages = (root.listFiles() ?: new File[0])
    .findAll { it.isDirectory() && new File(it, 'entities.xml').isFile() }
    .sort { it.name }

def stateOf = { String spaceKey ->
    def expected = manifest[spaceKey]
    def actual = currentState(spaceKey)
    boolean matches = expected != null && expected.pages == actual.pages
        && expected.blogPosts == actual.blogPosts
        && expected.attachments == actual.attachments
    [expected: expected, actual: actual, matches: matches, registered: isRegisteredAsSpace(spaceKey)]
}

def actionResults = []
if (request.getParameter('action') == 'register') {
    String only = request.getParameter('space')
    def targets = only ? packages.findAll { it.name == only } : packages
    targets.each { File pkg ->
        def st = stateOf(pkg.name)
        if (!st.matches) {
            actionResults << [key: pkg.name, ok: false,
                              message: '现状与 manifest 不一致，先确认导入完成'.toString()]
        } else {
            actionResults << [key: pkg.name] + registerAsSpace(pkg.name)
        }
    }
}

// ---- 5. 拼出预填好的 Filter Streams Converter 链接 ----
def converterLink = { File pkg ->
    def params = [input: 'confluence+xml', output: 'xwiki+instance']
    params['filter_input_properties_descriptor_source'] = "file:${pkg.absolutePath}"
    IMPORT_OPTIONS.each { key, value ->
        params["filter_input_properties_descriptor_${key}"] = value
    }
    def queryString = params.collect { k, v -> "${url(k)}=${url(v)}" }.join('&amp;')
    "${request.getContextPath()}/bin/view/Filter/?${queryString}"
}

// ---- 6. 渲染 ----
if (packages.isEmpty()) {
    out.append("<p class='ci-warn'>${esc(root.absolutePath)} 下没有找到任何包（缺少 <code>entities.xml</code>）。</p>")
    emit "{{html}}${out}{{/html}}"
    return
}

actionResults.each { r ->
    out.append("<div class='ci-box ${r.ok ? 'ci-box-ok' : 'ci-box-warn'}'>")
    out.append("<strong>${esc(r.key)}</strong>：${esc(r.message)}")
    out.append("</div>")
}
if (!actionResults.isEmpty()) {
    out.append("<p><a href='${request.getContextPath()}/bin/view/${esc(doc.fullName)}${dirQuery}'>")
    out.append("刷新查看结果 →</a></p>")
}

def rows = packages.collect { File pkg -> [pkg: pkg, st: stateOf(pkg.name)] }
def pendingRegister = rows.findAll { it.st.matches && !it.st.registered }
int done = rows.count { it.st.matches && it.st.registered }

out.append("<p>迁移目录：<code>${esc(root.absolutePath)}</code>；共 <strong>${packages.size()}</strong> 个包。")
out.append("「导入」会打开已经填好参数的 Filter Streams Converter，<strong>还需你在那里点一次 Convert</strong>；")
out.append("导入完成、数字对上之后，再点「注册为空间」把 <code>&lt;SpaceKey&gt;.WebHome</code> 标记成你们自定义的空间。</p>")

if (!pendingRegister.isEmpty()) {
    out.append("<form method='post' action='${request.getContextPath()}/bin/view/${esc(doc.fullName)}${dirQuery}' style='margin:10px 0'>")
    out.append("<input type='hidden' name='action' value='register'/>")
    if (dirParam) {
        out.append("<input type='hidden' name='dir' value='${esc(dirParam)}'/>")
    }
    out.append("<input type='submit' class='button' value='一键注册全部已完成的空间（${pendingRegister.size()} 个）'/>")
    out.append("</form>")
}

out.append("<table class='ci-table'><tr>")
out.append("<th>空间</th><th>期望（manifest.csv）</th><th>现状（XWiki）</th><th>状态</th><th>操作</th></tr>")

rows.each { row ->
    String key = row.pkg.name
    def expected = row.st.expected
    def actual = row.st.actual

    String status
    String actions
    if (row.st.matches && row.st.registered) {
        status = "<span class='ci-ok'>✓ 已导入并已注册空间</span>"
        actions = "—"
    } else if (row.st.matches) {
        status = "<span class='ci-warn'>已导入，待注册空间</span>"
        actions = "<form method='post' action='${request.getContextPath()}/bin/view/${esc(doc.fullName)}${dirQuery}' style='display:inline'>" +
            "<input type='hidden' name='action' value='register'/>" +
            "<input type='hidden' name='space' value='${esc(key)}'/>" +
            (dirParam ? "<input type='hidden' name='dir' value='${esc(dirParam)}'/>" : '') +
            "<input type='submit' class='button' value='注册为空间'/></form>"
    } else if (actual.pages == 0 && actual.blogPosts == 0 && actual.attachments == 0) {
        status = "<span class='ci-todo'>未导入</span>"
        actions = "<a class='button' href='${converterLink(row.pkg)}'>导入 →</a>"
    } else {
        status = "<span class='ci-warn'>⚠ 与清单不一致</span>"
        actions = "<a class='button' href='${converterLink(row.pkg)}'>重新导入 →</a>"
    }

    String expectedText = expected == null
        ? "<span class='ci-warn'>清单里没有这个空间</span>"
        : "${expected.pages} 页 / ${expected.blogPosts} 博客 / ${expected.attachments} 附件 / ${expected.bytes} 字节"
            .toString()

    out.append("<tr>")
    out.append("<td><code>${esc(key)}</code></td>")
    out.append("<td>${expectedText}</td>")
    out.append("<td>${actual.pages} 页 / ${actual.blogPosts} 博客 / ${actual.attachments} 附件 / "
        + "${actual.bytes} 字节</td>")
    out.append("<td>${status}</td>")
    out.append("<td>${actions}</td>")
    out.append("</tr>")
}
out.append("</table>")

out.append("<p>进度：<strong>${done}/${packages.size()}</strong> 个包已导入并注册为空间。")
out.append("数字对不上时先看 Filter 页面的作业状态与 XWiki 日志，再决定是否重导")
out.append("（导入器默认会清掉上次导入产生的页面，可重复执行）。</p>")

emit "{{html}}${out}{{/html}}"
{{/groovy}}
