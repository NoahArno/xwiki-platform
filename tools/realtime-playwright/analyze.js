#!/usr/bin/env node
/**
 * XWiki Realtime Playwright 压测结果分析器
 *
 * 解析一个或多个 artifacts 目录中的 events.json,统计所有输入耗时:
 *   - 汇总统计(总数、max/avg/p50/p95/p99)
 *   - 每用户统计
 *   - 列出全部 slow(>=500ms)与 severe(>=2000ms)输入的明细
 *   - 导出 CSV(全部输入,带分类标记),方便 Excel/WPS 做数据统计
 *
 * 用法:
 *   node analyze.js [artifactsDir1 [artifactsDir2 ...]]
 *   不传参数时默认分析 ./artifacts
 *   传多个目录可对比多次压测(例如修复前后)
 *
 * 阈值与 realtime-wysiwyg-load.js 保持一致:
 *   slow   >= 500ms
 *   severe >= 2000ms
 */
import fs from 'node:fs';
import path from 'node:path';

const SLOW_THRESHOLD = 500;
const SEVERE_THRESHOLD = 2000;

const dirs = process.argv.slice(2);
if (dirs.length === 0) {
  dirs.push('artifacts');
}

function readJson(file) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (e) {
    return null;
  }
}

function percentile(sorted, p) {
  if (sorted.length === 0) {
    return 0;
  }
  const idx = Math.min(sorted.length - 1, Math.max(0, Math.ceil(p * sorted.length) - 1));
  return sorted[idx];
}

function classify(durationMs) {
  if (durationMs >= SEVERE_THRESHOLD) {
    return 'severe';
  }
  if (durationMs >= SLOW_THRESHOLD) {
    return 'slow';
  }
  return 'ok';
}

const runs = [];
for (const dir of dirs) {
  const events = readJson(path.join(dir, 'events.json'));
  if (!events) {
    console.log(`[跳过] ${dir}: 找不到 events.json`);
    continue;
  }
  const markers = events
    .filter(e => e.type === 'typed-marker')
    .map(e => ({ time: e.time, userId: e.userId, marker: e.marker, durationMs: e.durationMs }));
  const durations = markers.map(m => m.durationMs).sort((a, b) => a - b);
  const slow = markers.filter(m => m.durationMs >= SLOW_THRESHOLD);
  const severe = markers.filter(m => m.durationMs >= SEVERE_THRESHOLD);
  runs.push({ dir, markers, durations, slow, severe });
}

if (runs.length === 0) {
  console.log('没有找到任何可分析的目录。用法: node analyze.js [artifactsDir]');
  process.exit(1);
}

const line = '='.repeat(78);
console.log(line);
console.log(`XWiki Realtime 输入耗时统计 (slow >= ${SLOW_THRESHOLD}ms, severe >= ${SEVERE_THRESHOLD}ms)`);
console.log(line);

let anySevere = false;
const csvRows = [];
for (const run of runs) {
  const { dir, markers, durations, slow, severe } = run;
  const total = markers.length;
  const sum = durations.reduce((acc, d) => acc + d, 0);
  const avg = total > 0 ? Math.round(sum / total) : 0;
  const max = total > 0 ? durations[durations.length - 1] : 0;
  const summary = readJson(path.join(dir, 'summary.json'));

  console.log(`\n===== 运行目录: ${dir} =====`);
  console.log(`  输入总数:        ${total}`);
  if (total === 0) {
    console.log('  (没有 typed-marker 事件,可能不是 typing 模式或运行未完成)');
    continue;
  }
  console.log(`  耗时: max=${max}ms  avg=${avg}ms  p50=${percentile(durations, 0.5)}ms  ` +
    `p95=${percentile(durations, 0.95)}ms  p99=${percentile(durations, 0.99)}ms`);
  console.log(`  slow  (>=${SLOW_THRESHOLD}ms): ${slow.length}`);
  console.log(`  severe(>=${SEVERE_THRESHOLD}ms): ${severe.length}`);
  if (summary) {
    console.log(`  [与 summary.json 对比] slowInputs=${summary.slowInputs}  severeInputs=${summary.severeInputs}  ` +
      `maxInputDurationMs=${summary.maxInputDurationMs}`);
    if (summary.slowInputs !== slow.length || summary.severeInputs !== severe.length) {
      console.log('  [警告] 与 summary.json 的计数不一致,以 events.json 为准');
    }
  }

  // 每用户统计
  const byUser = {};
  for (const m of markers) {
    byUser[m.userId] = byUser[m.userId] || [];
    byUser[m.userId].push(m.durationMs);
  }
  console.log('  每用户:');
  for (const [userId, ds] of Object.entries(byUser)) {
    ds.sort((a, b) => a - b);
    console.log(`    ${userId}: 总数=${ds.length}  max=${ds[ds.length - 1]}ms  ` +
      `avg=${Math.round(ds.reduce((a, b) => a + b, 0) / ds.length)}ms  ` +
      `slow=${ds.filter(d => d >= SLOW_THRESHOLD).length}  ` +
      `severe=${ds.filter(d => d >= SEVERE_THRESHOLD).length}`);
  }

  // 全部 slow/severe 明细
  const detail = [...slow].sort((a, b) => b.durationMs - a.durationMs);
  if (detail.length > 0) {
    console.log(`  全部 slow/severe 明细(${detail.length} 条,按耗时降序):`);
    for (const m of detail) {
      console.log(`    [${m.time}] ${m.userId}  ${m.marker}  ${m.durationMs}ms  (${classify(m.durationMs)})`);
    }
  } else {
    console.log('  无 slow/severe 输入');
  }

  // CSV 行(全部输入,带分类)
  for (const m of markers) {
    csvRows.push([dir, m.time, m.userId, m.marker, m.durationMs, classify(m.durationMs)]);
  }
  if (severe.length > 0) {
    anySevere = true;
  }
}

// 写 CSV
const ts = new Date();
const pad = n => String(n).padStart(2, '0');
const csvName = `stats_${ts.getFullYear()}${pad(ts.getMonth() + 1)}${pad(ts.getDate())}` +
  `_${pad(ts.getHours())}${pad(ts.getMinutes())}${pad(ts.getSeconds())}.csv`;
const csvHeader = 'run,time,userId,marker,durationMs,classification';
const csvContent = [csvHeader, ...csvRows.map(r => r.map(v => `"${String(v).replace(/"/g, '""')}"`).join(','))].join('\n');
fs.writeFileSync(csvName, csvContent, 'utf8');

console.log(`\n${line}`);
console.log(`CSV 已导出: ${path.resolve(csvName)}  (${csvRows.length} 条输入记录)`);
console.log(line);

if (anySevere) {
  console.log('存在 severe 输入(>=2000ms),退出码 2');
  process.exit(2);
} else {
  console.log('无 severe 输入,退出码 0');
  process.exit(0);
}
