{{groovy}}
/*
 * Confluence 迁移包 —— 手动导入 + 空间注册页（XWiki 侧）。
 *
 * 部署：新建页面（建议 Migration.Import，语法 xwiki/2.1），把本文件全文粘进页面内容，
 * 用有编程权限的用户保存。
 *
 * 它把迁移在 B 侧的三件事都包了：
 *   1. 导入：列出包、显示 manifest 期望值 vs XWiki 现状，给出预填好参数的 Filter 转换链接；
 *   2. 个人空间：Confluence 的 ~user 空间在你们 fork 里是「<用户名>的个人空间」，
 *      导入前先把包里的空间 key 改过来（「准备个人空间包」），导入后按个人空间口径注册；
 *   3. 空间注册：导入对账通过后，在 <SpaceKey>.WebHome 上挂 SpaceCode.SpaceClass 对象，
 *      让它成为你们自定义的「空间」（等价于 Spaces.CreateSpace 做的事，只是对象已存在的空间它不让建）。
 *
 * 为什么空间注册放在这里而不是改 xwiki-platform：
 *   「是不是空间」的判定只有一条——顶层空间的 WebHome 上有没有 SpaceCode.SpaceClass 对象
 *   （SpaceDirectoryLivetableResults 与 SpaceManager 用的是同一口径）。
 *   所以注册只是给已有页面加一个对象，属于数据操作，用脚本 API 就能完成，
 *   不需要动 SpaceManager / CreateSpace 任何一个应用侧文件。
 *
 * 为什么个人空间要先「准备包」：
 *   判定与链接都在 Spaces.MyPersonalSpace / Spaces.UserProfilePersonalSpaceUIX：
 *   只有 <用户名>的个人空间.WebHome 存在、且上面 SpaceClass.type == 'personal' 时，
 *   XWiki.<用户名> 资料页才显示「进入我的个人空间」（否则显示「开通我的个人空间」）。
 *   而导入器只把 ~test01 的内容落在空间 ~test01（它不认识 XWiki 的个人空间约定），
 *   也没有参数能换目标空间：rootSpace 只是父空间前缀（会得到 root.~test01），
 *   spaceRenamingFormat 是防止覆盖已有空间的改名机制，不是指定目标空间用的。
 *   空间的身份在包里只出现在 entities.xml 的 Space 对象（key/lowerKey）与
 *   exportDescriptor.properties（spaceKey）——页面/博客/附件全是按空间 id 引用，
 *   所以改这两处就换掉落点，不需要任何链接改写，也不需要导入后的改名作业。
 *
 * 刻意不做的事：
 *   - 没有 cron、没有后台轮询、没有锁文件、不维护 ledger；
 *   - 页面加载不写任何数据；只有你在 Filter 页面点 Convert、或点本页的按钮才会写
 *     （「准备个人空间包」写的是包文件，「注册为空间」写的是 XWiki 数据，都是显式提交）；
 *   - 只有「现状与 manifest 完全一致」的包才允许注册，避免把没导完的空间标成已完成；
 *   - 不删数据：早期用旧脚本导进 ~user 空间的内容只在页面上提示，工具不动手清理。
 *
 * 注意：脚本宏的输出会被当成 wiki 语法再解析一次，所以生成的 HTML 必须整体包在 html 宏里。
 *
 * 注意：本页自己的链接与表单提交地址必须用 xwiki.getURL(doc.documentReference, ...) 生成。
 *   手拼 doc.fullName 会得到「<空间>.<页面>」，而 /bin/view/ 的路径只认「<空间>/<页面>」两段，
 *   单段会被当成空间名，于是提交后必然 404（"找不到页面"）。
 *   空间首页上症状最明显：它的 fullName 是 <Space>.WebHome，拼出来一定打不开。
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

/** Confluence 个人空间的 key 前缀。 */
final String PERSONAL_SPACE_PREFIX = '~'

/**
 * 个人空间名的兜底格式。正常走 fork 自己的翻译键 {@code spaces.personal.name}
 * （{@code {0}的个人空间}，见 SpaceCode/SpaceTranslations.xml），只在翻译缺失时才用到它。
 */
final String PERSONAL_SPACE_SUFFIX = '的个人空间'

/**
 * 「这个包已经按个人空间准备过」的标记文件，写在包目录里，内容是目标空间名。
 * A 侧重新导出并 rsync（--delete）覆盖后它会消失，页面自动回到「未准备」。
 */
final String PERSONAL_SPACE_MARKER = '.xwiki-personal-space'

/** 包里的两个空间身份文件。 */
final String ENTITIES_XML = 'entities.xml'
final String EXPORT_DESCRIPTOR = 'exportDescriptor.properties'

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

/**
 * 个人空间包在 Convert 时必须额外带的开关。
 * 导入器默认 overwriteProtectionMode=NONCONFLUENCE：目标空间里只要有非 Confluence 内容的文档，
 * 它就把这次导入改名（spaceRenamingFormat，默认加下划线后缀）而不是写进去。
 * 而个人空间天生就「已经有东西」（fork 建的空间标记页，或用户自己的笔记），
 * 不关掉它，你得到的是 test01的个人空间_ 之类，而不是合并。
 * NONE = 不做保护、直接写进去（同名页面以 Confluence 为准）。
 */
final Map<String, String> PERSONAL_IMPORT_OPTIONS = [overwriteProtectionMode: 'NONE']
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

// 本页的规范 URL（含 ?dir=）。交给 XWiki 生成，别自己拼 doc.fullName —— 理由见文件头注释。
String pageUrl = xwiki.getURL(doc.documentReference, 'view', dirParam ? "dir=${url(dirParam)}" : '')

// ---- 0. 个人空间：Confluence key <-> XWiki 空间名 ----
// 口径与 Spaces.MyPersonalSpace 一致：空间名 = 去掉 ~ 的用户名，按翻译键 spaces.personal.name 渲染。
def isPersonalPackage = { String spaceKey -> spaceKey != null && spaceKey.startsWith(PERSONAL_SPACE_PREFIX) }

def personalUserName = { String spaceKey -> spaceKey.substring(PERSONAL_SPACE_PREFIX.length()) }

def personalSpaceNameFor = { String userName ->
    def rendered = services.localization.render('spaces.personal.name', [userName])
    (rendered && rendered != 'spaces.personal.name') ? rendered : (userName + PERSONAL_SPACE_SUFFIX)
}

def personalDescriptionFor = { String userName ->
    def rendered = services.localization.render('spaces.personal.description', [userName])
    (rendered && rendered != 'spaces.personal.description') ? rendered : (userName + ' 的个人空间')
}

/** 包 key -> XWiki 里的目标空间名。个人空间换名，普通空间原样。 */
def targetSpaceOf = { String spaceKey ->
    isPersonalPackage(spaceKey) ? personalSpaceNameFor(personalUserName(spaceKey)) : spaceKey
}

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
//   Confluence 空间首页 -> <SpaceKey>.WebHome
//   其余页面            -> <SpaceKey>.<PageTitle>/WebHome（嵌套空间）
//   Confluence 博客     -> <SpaceKey>.Blog.<博客标题>（一个普通文档，不是 WebHome）
//   另外它还会自建 <SpaceKey>.Blog.WebHome 与 <SpaceKey>.WebPreferences 这两个"壳"文档。
// 所以数页面时要排掉博客空间本身，博客单独按 <SpaceKey>.Blog 下的非 WebHome 文档数算。
// 传入的 spaceKey 是「目标空间名」——个人空间要数 <用户名>的个人空间，不是 ~user。
//
// onlyImported=true 时只数导入器建的文档（带 Confluence.Code.ConfluencePageClass 详情对象）。
// 合并进一个已经在用的个人空间时必须这样数：那个空间里本来就有用户自己的页面，
// 甚至有他自己建的嵌套空间（也是 WebHome），混在一起数永远对不上 manifest。
def currentState = { String spaceKey, boolean onlyImported = false ->
    String blogSpacePrefix = spaceKey + BLOG_SPACE_SUFFIX
    def query = services.query.xwql(onlyImported
        ? 'select doc.fullName from Document doc, doc.object(Confluence.Code.ConfluencePageClass) as obj '
            + 'where doc.space = :key or doc.space like :nested'
        : 'select doc.fullName from Document doc where doc.space = :key or doc.space like :nested')
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

/** 期望值与一组现状计数是否一致。 */
def countsMatch = { def expected, def actual ->
    expected != null && expected.pages == actual.pages
        && expected.blogPosts == actual.blogPosts
        && expected.attachments == actual.attachments
}

// ---- 3. 空间注册 ----
// 判定口径与 Spaces.SpaceDirectoryLivetableResults / SpaceManager 完全一致：
// 顶层空间的 WebHome 上有没有 SpaceCode.SpaceClass 对象（个人空间还要求 type=personal，
// 因为资料页上的按钮认的正是这个值）。
def spaceHomeName = { String target -> "${target}.WebHome" }

def spaceObjectOf = { String spaceName ->
    def spaceDoc = xwiki.getDocument(spaceHomeName(spaceName))
    (spaceDoc == null || spaceDoc.isNew()) ? null : spaceDoc.getObject(SPACE_CLASS)
}

def isRegisteredAsSpace = { String spaceKey ->
    def spaceObj = spaceObjectOf(targetSpaceOf(spaceKey))
    if (spaceObj == null) {
        return false
    }
    return !isPersonalPackage(spaceKey) || spaceObj.get('type') == 'personal'
}

/** @return [ok:boolean, message:String] */
def registerAsSpace = { String spaceKey ->
    String target = targetSpaceOf(spaceKey)
    String fullName = spaceHomeName(target)
    def spaceDoc = xwiki.getDocument(fullName)

    if (spaceDoc == null || spaceDoc.isNew()) {
        return [ok: false, message: "${fullName} 不存在，先把包装进去".toString()]
    }

    boolean personal = isPersonalPackage(spaceKey)
    String userName = personal ? personalUserName(spaceKey) : null
    // 普通空间的标题就是导入器从 Confluence 带过来的空间名（实测 ds.WebHome -> "Demonstration Space"）；
    // 个人空间的名/键都必须是 <用户名>的个人空间，否则资料页的按钮不认。
    String spaceName = personal ? target : (spaceDoc.getTitle() ?: target)
    String spaceType = personal ? 'personal' : 'public'
    // 本工具不迁账号：个人空间的 owner 按「去 ~ 的用户名」写，用户还不存在也照写
    // （内网账号由 OA 同步，先迁内容后建人是常态）；普通空间只能记当前操作者。
    String owner = personal ? "xwiki:XWiki.${userName}".toString() : xcontext.getUser()

    def spaceObj = spaceDoc.getObject(SPACE_CLASS)
    if (spaceObj != null && (!personal || spaceObj.get('type') == 'personal')) {
        // 已经是（个人）空间。这里不逐字段比对再决定要不要存：owner / description 这两类属性
        // 用 obj.get() 读出来的是渲染后的显示（用户列表的 HTML、包了 html 宏的富文本），
        // 拿它跟我们要写的原始值比永远不会相等，会导致每次点击都白写一个新版本。
        return [ok: true, message: '已经是空间，无需重复注册'.toString()]
    }
    if (spaceObj == null) {
        spaceObj = spaceDoc.newObject(SPACE_CLASS)
        if (spaceObj == null) {
            return [ok: false, message: "无法创建 ${SPACE_CLASS} 对象（类文档缺失？）".toString()]
        }
    }

    // 走到这里只有两种情况：新建对象，或把一个已存在的普通空间改造成个人空间 —— 无条件写全字段。
    spaceObj.set('name', spaceName)
    spaceObj.set('key', target)
    spaceObj.set('type', spaceType)
    spaceObj.set('owner', owner)
    if (personal) {
        spaceObj.set('description', personalDescriptionFor(userName))
        // Spaces.MyPersonalSpace 建出来的空间首页，标题就是空间名（实测 test02的个人空间）
        spaceDoc.setTitle(target)
    }

    spaceDoc.save('Confluence 迁移：注册为空间')

    return [ok: true, message: (personal
        ? "已注册为个人空间（${fullName}，owner=${owner}）"
        : "已注册为空间（name=${spaceName}, type=${spaceType}）").toString()]
}

// ---- 4. 个人空间：把包改成「导入即落到 <用户名>的个人空间」 ----
// 只动两处空间身份：entities.xml 里 Space 对象的 key/lowerKey、exportDescriptor.properties 的 spaceKey。
// 页面/博客/附件都是按空间内部 id 引用的（实测），所以不需要改任何内容或链接。
// 流式逐行改写 + 写临时文件后原子改名；重复执行幂等。
/** @return [ok:boolean, message:String] */
def preparePackage = { File pkg ->
    String spaceKey = pkg.name
    if (!isPersonalPackage(spaceKey)) {
        return [ok: false, message: '不是个人空间包（key 不以 ~ 开头）'.toString()]
    }
    String target = targetSpaceOf(spaceKey)
    def entities = new File(pkg, ENTITIES_XML)
    if (!entities.isFile()) {
        return [ok: false, message: "${ENTITIES_XML} 不存在".toString()]
    }

    String oldCdata = "<![CDATA[${spaceKey}]]>"
    String newCdata = "<![CDATA[${target}]]>"
    boolean changed = false
    boolean sawKeyLine = false
    boolean alreadyTarget = false

    def tmp = new File(pkg, ENTITIES_XML + '.tmp')
    tmp.withWriter('UTF-8') { writer ->
        boolean inSpaceObject = false
        entities.eachLine('UTF-8') { String line ->
            if (inSpaceObject && line.contains('</object>')) {
                inSpaceObject = false
            } else if (inSpaceObject
                && (line.contains('<property name="key">') || line.contains('<property name="lowerKey">'))) {
                sawKeyLine = true
                if (line.contains(newCdata)) {
                    alreadyTarget = true
                } else if (line.contains(oldCdata)) {
                    line = line.replace(oldCdata, newCdata)
                    changed = true
                }
            } else if (line.contains('class="Space"')) {
                inSpaceObject = true
            }
            writer.write(line)
            writer.write('\n')
        }
    }

    if (!changed) {
        tmp.delete()
        if (!alreadyTarget) {
            return [ok: false,
                    message: "没在 ${ENTITIES_XML} 里找到 Space 对象的 key（格式和预期不一致，没有改动任何文件）".toString()]
        }
    } else if (!tmp.renameTo(entities)) {
        // 退路：同目录改名失败（极罕见）就直接覆盖
        entities.setText(tmp.getText('UTF-8'), 'UTF-8')
        tmp.delete()
    }

    // exportDescriptor.properties 的 spaceKey 导入器也读（判断这个包是哪个空间导出的），必须一起改
    def descriptor = new File(pkg, EXPORT_DESCRIPTOR)
    if (descriptor.isFile()) {
        boolean descriptorChanged = false
        def lines = descriptor.readLines('UTF-8').collect { String line ->
            if (line.startsWith('spaceKey=') && line.contains(spaceKey)) {
                descriptorChanged = true
                return 'spaceKey=' + target
            }
            return line
        }
        if (descriptorChanged) {
            def descriptorTmp = new File(pkg, EXPORT_DESCRIPTOR + '.tmp')
            descriptorTmp.setText(lines.join('\n') + '\n', 'UTF-8')
            if (!descriptorTmp.renameTo(descriptor)) {
                descriptor.setText(lines.join('\n') + '\n', 'UTF-8')
                descriptorTmp.delete()
            }
        }
    }

    // 标记文件：给页面判断「准备过没有」，也顺便记下目标空间名（rsync --delete 会清掉它）
    new File(pkg, PERSONAL_SPACE_MARKER).setText(target + '\n', 'UTF-8')

    return [ok: true, message: (changed
        ? "已准备：包空间 ${spaceKey} -> ${target}（${ENTITIES_XML} + ${EXPORT_DESCRIPTOR} 已改写）"
        : "已经是目标空间 ${target}，无需重复准备").toString()]
}

def isPrepared = { String spaceKey ->
    if (!isPersonalPackage(spaceKey)) {
        return true
    }
    def marker = new File(root, "${spaceKey}/${PERSONAL_SPACE_MARKER}")
    marker.isFile() && marker.getText('UTF-8').trim() == targetSpaceOf(spaceKey)
}

// ---- 5. 包清单与每个包的期望/现状 ----
def packages = (root.listFiles() ?: new File[0])
    .findAll { it.isDirectory() && new File(it, ENTITIES_XML).isFile() }
    .sort { it.name }

def stateOf = { String spaceKey ->
    def expected = manifest[spaceKey]
    String target = targetSpaceOf(spaceKey)
    boolean personal = isPersonalPackage(spaceKey)
    // actual = 目标空间里的全部内容；个人空间里可能混着用户自己的页面
    def actual = currentState(target)
    // imported = 只数导入器建的文档。个人空间按它对账/判断「导没导过」，
    // 这样用户在个人空间里已有的页面（含他自己建的嵌套空间）不会干扰对账。
    def imported = personal ? currentState(target, true) : actual
    // 旧脚本把 ~user 导进了 ~user 本身；切到个人空间后那些内容就是孤儿，只提示不清理
    def legacy = personal ? currentState(spaceKey) : null
    // 两个口径有一个对上就算对上：万一某些内容（例如博客）没带 Confluence 详情对象，
    // 只按 imported 判会永久「不一致」而卡住注册，所以全部内容口径仍然认。
    boolean matches = countsMatch(expected, imported) || countsMatch(expected, actual)
    [expected: expected, actual: actual, imported: imported, legacy: legacy, matches: matches,
     registered: isRegisteredAsSpace(spaceKey), prepared: isPrepared(spaceKey)]
}

// ---- 6. 拼出预填好的 Filter Streams Converter 链接 ----
def converterLink = { File pkg ->
    def params = [input: 'confluence+xml', output: 'xwiki+instance']
    params['filter_input_properties_descriptor_source'] = "file:${pkg.absolutePath}"
    Map<String, String> options =
        isPersonalPackage(pkg.name) ? (IMPORT_OPTIONS + PERSONAL_IMPORT_OPTIONS) : IMPORT_OPTIONS
    options.each { key, value ->
        params["filter_input_properties_descriptor_${key}"] = value
    }
    // 分隔符用裸 & 而不是 &amp;：脚本宏的输出整体还会被再转义一次（这一页的 HTML 会经 HTML 宏
    // 重新解析/序列化，& 会变成 &amp;）。这里写 &amp; 就会二次转义成 &amp;amp;，
    // 浏览器把后面的参数名读成 amp;filter_input_properties_descriptor_...，链接等于没预填。
    def queryString = params.collect { k, v -> "${url(k)}=${url(v)}" }.join('&')
    "${request.getContextPath()}/bin/view/Filter/?${queryString}"
}

// ---- 7. 处理页面上的显式提交（准备包 / 注册）----
def actionForm = { String action, String value, String key ->
    "<form method='post' action='${esc(pageUrl)}' style='display:inline'>" +
        "<input type='hidden' name='action' value='${esc(action)}'/>" +
        (key ? "<input type='hidden' name='space' value='${esc(key)}'/>" : '') +
        (dirParam ? "<input type='hidden' name='dir' value='${esc(dirParam)}'/>" : '') +
        "<input type='submit' class='button' value='${esc(value)}'/></form>"
}

def actionResults = []
String action = request.getParameter('action')
if (action == 'prepare' || action == 'register') {
    String only = request.getParameter('space')
    def targets = only ? packages.findAll { it.name == only } : packages
    if (action == 'prepare') {
        targets.findAll { isPersonalPackage(it.name) }.each { File pkg ->
            def result = preparePackage(pkg)
            actionResults << [key: pkg.name] + result + (result.ok ? [link: converterLink(pkg)] : [:])
        }
    } else {
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
}

// ---- 8. 渲染 ----
if (packages.isEmpty()) {
    out.append("<p class='ci-warn'>${esc(root.absolutePath)} 下没有找到任何包（缺少 <code>${ENTITIES_XML}</code>）。</p>")
    emit "{{html}}${out}{{/html}}"
    return
}

actionResults.each { r ->
    out.append("<div class='ci-box ${r.ok ? 'ci-box-ok' : 'ci-box-warn'}'>")
    out.append("<strong>${esc(r.key)}</strong>：${esc(r.message)}")
    if (r.link) {
        // 不套 esc()：查询串是以裸 & 分隔的，esc 会把它变成 &amp; 再被转义一次（同 converterLink 注释）
        out.append(" <a href='${r.link}'>去 Convert →</a>")
    }
    out.append("</div>")
}
if (!actionResults.isEmpty()) {
    out.append("<p><a href='${esc(pageUrl)}'>")
    out.append("刷新查看结果 →</a></p>")
}

def rows = packages.collect { File pkg -> [pkg: pkg, st: stateOf(pkg.name)] }
def pendingRegister = rows.findAll { it.st.matches && !it.st.registered }
def pendingPrepare = rows.findAll { isPersonalPackage(it.pkg.name) && !it.st.prepared }
int done = rows.count { it.st.matches && it.st.registered }

out.append("<p>迁移目录：<code>${esc(root.absolutePath)}</code>；共 <strong>${packages.size()}</strong> 个包。")
out.append("「导入」会打开已经填好参数的 Filter Streams Converter，<strong>还需你在那里点一次 Convert</strong>；")
out.append("导入完成、数字对上之后，再点「注册为空间」。以 <code>~</code> 开头的 Confluence 个人空间会落到 ")
out.append("<code>&lt;用户名&gt;的个人空间</code>，第一次要先点「准备个人空间包」把包里的空间 key 改过来")
out.append("（合并进已存在的个人空间时，导入器默认会改名回避，所以这几个包会额外带 ")
out.append("<code>overwriteProtectionMode=NONE</code>）。</p>")

if (!pendingPrepare.isEmpty()) {
    out.append(actionForm('prepare', "一键准备全部个人空间包（${pendingPrepare.size()} 个）", null))
}
if (!pendingRegister.isEmpty()) {
    out.append(actionForm('register', "一键注册全部已完成的空间（${pendingRegister.size()} 个）", null))
}

out.append("<table class='ci-table'><tr>")
out.append("<th>空间</th><th>期望（manifest.csv）</th><th>现状（XWiki）</th><th>状态</th><th>操作</th></tr>")

rows.each { row ->
    String key = row.pkg.name
    def expected = row.st.expected
    def actual = row.st.actual
    def imported = row.st.imported
    def legacy = row.st.legacy
    boolean personal = isPersonalPackage(key)
    boolean prepared = row.st.prepared
    String target = targetSpaceOf(key)
    // 个人空间按「导入器建的内容」显示与判断是否有内容，用户自己的页面单列出来
    def counted = personal ? imported : actual
    int otherPages = personal ? (actual.pages - imported.pages) : 0

    String spaceCell = "<code>${esc(key)}</code>"
    if (personal) {
        spaceCell += "<br/><span class='ci-todo'>→ <code>${esc(target)}</code>" +
            (prepared ? '' : '（未准备）') + "</span>"
    }

    String legacyHint = ''
    if (legacy != null && (legacy.pages + legacy.blogPosts + legacy.attachments) > 0) {
        boolean legacyRegistered = spaceObjectOf(key) != null
        legacyHint = "<br/><span class='ci-warn'>检测到旧的 <code>${esc(key)}</code> 空间" +
            "（${legacy.pages} 页 / ${legacy.blogPosts} 博客 / ${legacy.attachments} 附件" +
            (legacyRegistered ? '，而且它已被注册成了空间' : '') + "）：" +
            "旧脚本导入留下的孤儿，确认无用后请手工清理，工具不动手删</span>"
    }

    String status
    String actions
    if (row.st.matches && row.st.registered) {
        status = "<span class='ci-ok'>✓ 已导入并已注册${personal ? '个人' : ''}空间</span>"
        actions = "—"
    } else if (row.st.matches) {
        status = "<span class='ci-warn'>已导入，待注册${personal ? '个人' : ''}空间</span>"
        actions = actionForm('register', '注册为空间', key)
    } else if (counted.pages == 0 && counted.blogPosts == 0 && counted.attachments == 0) {
        status = "<span class='ci-todo'>${personal && !prepared ? '个人空间包未准备' : '未导入'}</span>"
        actions = personal && !prepared
            ? actionForm('prepare', '准备个人空间包', key)
            : "<a class='button' href='${converterLink(row.pkg)}'>导入 →</a>"
    } else {
        status = "<span class='ci-warn'>⚠ 与清单不一致</span>"
        actions = personal && !prepared
            ? actionForm('prepare', '准备个人空间包', key)
            : "<a class='button' href='${converterLink(row.pkg)}'>重新导入 →</a>"
    }

    String expectedText = expected == null
        ? "<span class='ci-warn'>清单里没有这个空间</span>"
        : "${expected.pages} 页 / ${expected.blogPosts} 博客 / ${expected.attachments} 附件 / ${expected.bytes} 字节"
            .toString()

    // 个人空间里用户自己已有的页面也列出来，免得「现状」和状态看起来对不上
    String otherHint = otherPages > 0
        ? "<br/><span class='ci-todo'>另有 ${otherPages} 个非迁移页面（用户自己的内容，不计入对账）</span>"
        : ''

    out.append("<tr>")
    out.append("<td>${spaceCell}</td>")
    out.append("<td>${expectedText}</td>")
    out.append("<td>${counted.pages} 页 / ${counted.blogPosts} 博客 / ${counted.attachments} 附件 / "
        + "${counted.bytes} 字节${otherHint}</td>")
    out.append("<td>${status}${legacyHint}</td>")
    out.append("<td>${actions}</td>")
    out.append("</tr>")
}
out.append("</table>")

out.append("<p>进度：<strong>${done}/${packages.size()}</strong> 个包已导入并注册为空间。")
out.append("数字对不上时先看 Filter 页面的作业状态与 XWiki 日志，再决定是否重导")
out.append("（导入器默认会清掉上次导入产生的页面，可重复执行）。</p>")

emit "{{html}}${out}{{/html}}"
{{/groovy}}
