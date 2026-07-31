# XWiki Realtime Playwright 诊断脚本

这个目录包含一个独立的 Playwright 诊断脚本，用来模拟 8 个用户同时编辑同一个 XWiki Realtime WYSIWYG 页面。它主要用于复现和分析协同编辑中的输入卡顿、输入丢失、表格右键菜单异常、WebSocket 断连和保存异常等问题，不建议直接放到 CI 中长期运行。

脚本会为每个用户打开一个独立的浏览器上下文，分别登录账号，进入同一个编辑页面，持续输入唯一标记文本，可选地对表格单元格执行右键操作，并记录控制台、网络请求、WebSocket、输入耗时等诊断信息。运行结束后，它会检查所有用户输入的标记文本是否仍然能在页面中看到。

## 准备

```bash
cd tools/realtime-playwright
npm install
cp config.example.json config.json
```

编辑 `config.json`：

- 把 `baseURL` 改成你的 XWiki 基础地址，例如 `http://xwiki.example.com/xwiki`。
- 把 `editURL` 改成问题页面的 WYSIWYG 编辑地址。
- 把 8 个用户的 `username` 和 `password` 替换成测试账号。
- 第一次运行建议保持 `headless` 为 `false`，这样可以直接观察浏览器行为。

`browser.channel` 可选：

- 留空或省略：使用 Playwright 自带 Chromium（默认）。
- `"chrome"`：使用系统安装的 Google Chrome（例如内网机器上的 Chrome 146）。
- `"msedge"`：使用系统安装的 Microsoft Edge。

## 运行

```bash
node realtime-wysiwyg-load.js --config config.json
```

脚本退出码含义：

- `0`：没有检测到严重输入延迟，也没有发现输入标记丢失。
- `2`：至少有一个输入标记丢失，或某次输入耗时超过 2000ms。
- `1`：脚本配置、登录、页面定位等运行错误。

## 随机持续编辑模式

如果想让脚本像真实用户一样在给定编辑页面里低频随机修改，可以开启 `randomEditing`：

```json
"randomEditing": {
  "enabled": true,
  "durationMs": 0,
  "minPauseMs": 3000,
  "maxPauseMs": 8000,
  "users": "all",
  "markerPrefix": "RANDOM",
  "actions": {
    "insertText": 60,
    "deleteText": 10,
    "newline": 10,
    "tableContextMenu": 10,
    "tableCellText": 10
  }
}
```

- `durationMs: 0`：一直运行，直到按 `Ctrl+C`。
- `durationMs > 0`：运行到指定毫秒数后自动停止。
- `minPauseMs` / `maxPauseMs`：每个用户两次随机动作之间的等待区间。
- `actions`：随机动作权重。页面没有表格时，表格动作会被记录为 skipped，脚本继续运行。

随机持续编辑模式默认不保存页面。需要保存时开启 `save.enabled`。

## 输出

默认输出目录是 `artifacts/`：

- `summary.json`：汇总结果，包括慢输入次数、严重慢输入次数、丢失标记、事件计数等。
- `events.json`：按时间记录的控制台、网络、WebSocket、输入和校验事件。
- `marker-verification.json`：每个用户视角下丢失了哪些输入标记。
- `U*/trace.zip`：每个用户的 Playwright trace。
- `U*/final.png`、`U*/after-typing.png`：用于人工检查的截图。
- `U*/video/`：浏览器录屏。

查看 trace：

```bash
npx playwright show-trace artifacts/U1/trace.zip
```

## 建议测试矩阵

建议按下面顺序跑，方便隔离问题：

1. 双用户冒烟测试：把 `minUsers` 改成 `2`，并只保留两个用户。
2. 8 个用户编辑简单文本页面。
3. 8 个用户编辑包含大表格的页面。
4. 8 个用户编辑真实周报页面。
5. 在确认输入稳定性之后，再开启 `save.enabled` 测试保存链路。

## Selector 调整

默认配置会依次尝试 CKEditor、编辑器 iframe、通用 `contenteditable` 和源码 textarea。如果脚本报错 `Unable to locate a CKEditor/contenteditable editor`，需要检查编辑页面 DOM，并调整 `selectors.editor`。

表格诊断默认使用 `td` 和 `th` 作为 `selectors.tableCell`。如果编辑器在 iframe 中，脚本也会搜索所有 frame。

## 如何看结果

重点看 `summary.json` 里的字段：

- `missingMarkers`：输入过、但最终在某个用户视角下看不到的标记。这个用于判断是否有输入丢失或同步丢失。
- `severeInputs`：输入耗时超过 2000ms 的次数。
- `slowInputs`：输入耗时超过 500ms 的次数。
- `tableResult.contextMenus`：右键表格单元格后成功看到菜单的次数。

如果 `missingMarkers` 为空，但 `slowInputs` 很高，问题更可能是浏览器主线程卡顿，而不是内容丢失。如果同一时间附近出现 `websocket-close`、`request-failed` 或 `http-error` 事件，需要结合 trace、浏览器 Console 和服务端日志一起看。

## 安全提醒

请只在测试页面或生产页面的测试副本上运行。脚本会输入大量标记文本；如果开启 `save.enabled`，还会尝试保存页面。

## 内网 Windows 离线使用

如果运行机器在内网、无法访问外网（例如公司 Windows 电脑），可以使用预打包的离线目录：

1. 在能联网的机器上执行 `npm install`（或使用仓库内已生成好的 `offline-bundle/`）。
2. 把 `offline-bundle/xwiki-realtime-playwright-offline-windows/` 整个文件夹拷贝到内网 Windows 机器。
3. 双击 `run-verify.bat` 自检，编辑 `app/config.json` 后双击 `run.bat` 运行。

离线包已内置 Node.js（win-x64）、playwright 依赖、Chromium 151 和 ffmpeg，完全不需要外网；
也支持通过 `browser.channel: "chrome"` 复用内网已安装的 Google Chrome。详见离线包内 `README-OFFLINE.md`。
