# XWiki Excerpt / Excerpt Include 实现记录

## 目标

在 XWiki 中补充类似 Confluence 的两个宏：

- `excerpt`
  - 用于在页面中定义一段可复用的摘录内容
  - 需要支持在页面编辑器中直接编辑内容，而不是只能通过宏参数弹窗编辑
- `excerpt-include`
  - 用于在另一页面中引入 `excerpt`
  - 需要通过页面引用找到目标页中的摘录内容并渲染

本次实现落在以下文件：

- [Excerpt.xml](/mydata/work/xwiki_migrate/sourcecode/xwiki-platform/xwiki-platform-core/xwiki-platform-rendering/xwiki-platform-rendering-ui/src/main/resources/Macros/Excerpt.xml)
- [ExcerptInclude.xml](/mydata/work/xwiki_migrate/sourcecode/xwiki-platform/xwiki-platform-core/xwiki-platform-rendering/xwiki-platform-rendering-ui/src/main/resources/Macros/ExcerptInclude.xml)

## 遇到的核心问题

### 1. `excerpt` 渲染能工作，但不能在页面中直接编辑

最开始的实现思路是：

- 从 `$xcontext.macro.content` 读取宏体内容
- 用 `$services.rendering.parse(...)` 重新解析
- 再把结果塞回 `$xcontext.macro.result`

这能让内容正常显示，但对 CKEditor 来说，这只是“宏生成出来的结果”，不是“宏的可编辑内容区”。

所以会出现：

- 页面中能看到内容
- 但不能像 `box`、`expandable` 这一类内容宏一样，直接在页面里编辑

### 2. 为了让 `excerpt-include` 找到摘录，给 `excerpt` 增加边界标记后，又影响了编辑体验

中间尝试过给 `excerpt` 输出额外的隐藏边界，例如：

- 开始标记
- 宏体内容
- 结束标记

这样 `excerpt-include` 可以从渲染结果中截取边界之间的内容，但副作用很明显：

- 有时会把宏后续内容也吞进去
- 有时又会导致页面内不可直接编辑

根因是：

- `wikimacrocontent` 的识别依赖 XWiki 渲染链路和 CKEditor 的 nested editable 机制
- 一旦在宏体外再包过多额外结构，尤其是拆成多个 `html` 块或混入额外块级包装，就容易破坏编辑器识别

### 3. `excerpt-include` 直接扫原始源码不可靠

早期实现里，`excerpt-include` 通过正则从目标页源码里查找：

```xwiki
{{excerpt}}...{{/excerpt}}
```

这种方案有几个问题：

- 对源码格式过于敏感
- 受换行、参数、空格、语法变化影响
- 一旦页面经过编辑器或转换链处理，结果容易和预期不一致

因此不适合作为稳定实现。

## 关键排查结论

### 1. `excerpt` 要想支持页面内直接编辑，必须使用 `{{wikimacrocontent/}}`

XWiki 的 wiki macro 若想让宏内容成为编辑器可识别的“嵌套可编辑区”，不能手动 parse 宏内容并返回结果，而应该显式使用：

```xwiki
{{wikimacrocontent/}}
```

底层相关实现：

- `WikiMacroContentMacro`
- `DefaultWikiMacroRenderer`
- `xwiki-macro` CKEditor 插件

其中 CKEditor 只会把符合条件的块级 `non-generated-content` 区域初始化成 nested editable。

所以最终 `excerpt` 的实现必须尽量保持极简，不额外增加会干扰识别的结构。

### 2. `excerpt-include` 应该从目标页的 XDOM 中找宏块，而不是扫源码或扫最终 HTML

目标页本身已经能被 XWiki 解析成结构化 XDOM，所以更稳的方式是：

- 读取目标页的 XDOM
- 找出第一个 `excerpt` 宏块
- 直接取其 `content`
- 按目标页语法重新 parse 并输出

这比“扫源码字符串”或“扫渲染后的 HTML”都更稳定。

## 最终实现方案

### `excerpt`

最终保留为最小实现：

```xwiki
{{wikimacrocontent/}}
```

原因：

- 这是最符合 XWiki 内容宏编辑机制的写法
- 可以恢复页面中直接编辑
- 不会再因为额外包装导致后续内容被吞进去

### `excerpt-include`

最终逻辑：

1. 读取 `reference`，如果为空则回退到 `page`
2. 加载目标文档
3. 通过 `targetDoc.getDocument().getXDOM().getBlocks('class:MacroBlock', 'DESCENDANT')` 遍历宏块
4. 找到 `id == 'excerpt'` 的第一个宏块
5. 读取其 `content`
6. 使用目标页的语法重新解析并输出

当前关键代码类似：

```velocity
#set ($excerptContent = '')
#foreach ($macroBlock in $targetDoc.getDocument().getXDOM().getBlocks('class:MacroBlock', 'DESCENDANT'))
  #if ($macroBlock.id == 'excerpt')
    #set ($excerptContent = "$!macroBlock.content")
    #break
  #end
#end
#if ($excerptContent != '')
  #set ($targetSyntax = $targetDoc.syntax.toIdString())
  #set ($xcontext.macro.result = $services.rendering.parse($excerptContent, $targetSyntax).children)
#end
```

## 为什么这个方案最终稳定

职责被彻底拆开了：

- `excerpt`
  - 只负责“定义一段可编辑的宏内容”
  - 不负责额外边界、标记、提取逻辑
- `excerpt-include`
  - 只负责“从目标页结构化内容里取出 excerpt 的原始宏体并渲染”

这样避免了两个常见冲突：

- 为了 include 提取方便，去破坏 `excerpt` 的编辑结构
- 为了让 `excerpt` 可编辑，继续依赖脆弱的字符串正则提取

## 失败方案总结

以下方案都试过，但不适合作为最终实现：

- 手工 parse `$xcontext.macro.content`
  - 渲染可以，页面内不可直接编辑
- 给 `excerpt` 增加隐藏边界，再让 `excerpt-include` 从渲染 HTML 中截取
  - 容易吞后续内容
  - 容易破坏 CKEditor 对 nested editable 的识别
- 从目标页原始源码里正则查找 `{{excerpt}}...{{/excerpt}}`
  - 对源码格式过于敏感，不稳定

## 后续扩展建议

如果后续还要继续增强，可以考虑：

1. 支持多个 `excerpt`
   - 当前实现默认取目标页第一个 `excerpt`
   - 如果要兼容 Confluence 更复杂场景，可以增加 `name` 参数

2. 增加更友好的引用提示
   - `reference` / `page` 已经使用 `DocumentReference` 类型
   - 可以继续验证编辑器侧的自动补全体验

3. 增加测试
   - `excerpt` 可编辑性这类问题更适合补 UI / 集成测试
   - `excerpt-include` 的提取逻辑适合补渲染层测试

## 本次经验

在 XWiki 里做“内容宏”时，有一个原则很重要：

- 不要把“宏体内容”当成普通字符串自己重新 parse
- 应优先接入 XWiki 已有的 `wikimacrocontent` / XDOM / macro block 机制

否则很容易出现：

- 视图渲染正常
- 编辑态异常
- 保存后结构错乱
- include / 引用场景不稳定

这次 `excerpt` / `excerpt-include` 的问题，本质上就是这个原则的直接体现。
