#!/usr/bin/env bash
#
# 验证 B 侧导入脚本 —— 在没有可认证的 XWiki 的情况下，尽可能把能查的错都查出来。
#
# 做法：
#   1. 用 XWiki 自带的 groovy jar 与整个 WEB-INF/lib 作为 classpath，编译 Groovy 脚本
#      （验证语法与所有 import 是否成立）
#   2. 把脚本里每一处 XWiki API 调用用静态类型重写成 Java 并编译
#      （Groovy 编译查不出方法签名错误，javac 可以）
#
# 用法：
#   ./verify.sh /path/to/apache-tomcat/webapps/xwiki
#
set -euo pipefail

XWIKI_WEBAPP="${1:-}"
if [[ -z "$XWIKI_WEBAPP" ]]; then
    echo "用法: $0 <xwiki webapp 目录，例如 .../webapps/xwiki>" >&2
    exit 2
fi

LIB_DIR="$XWIKI_WEBAPP/WEB-INF/lib"
if [[ ! -d "$LIB_DIR" ]]; then
    echo "找不到 $LIB_DIR" >&2
    exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

echo "== 1/2 Groovy 语法与 import 检查 =="
cat > "$WORK_DIR/SyntaxCheck.java" <<'JAVA'
import groovy.lang.GroovyClassLoader;
import java.io.File;

public class SyntaxCheck {
    public static void main(String[] args) throws Exception {
        File script = new File(args[0]);
        try (GroovyClassLoader loader = new GroovyClassLoader(SyntaxCheck.class.getClassLoader())) {
            loader.parseClass(script);
            System.out.println("OK");
        }
    }
}
JAVA
javac -cp "$LIB_DIR/*" -d "$WORK_DIR" "$WORK_DIR/SyntaxCheck.java"
java -cp "$WORK_DIR:$LIB_DIR/*" SyntaxCheck "$SCRIPT_DIR/ImportSchedulerJob.groovy"
echo "   Groovy 脚本编译通过"

echo "== 2/2 XWiki API 签名检查 =="
javac -cp "$LIB_DIR/*" -d "$WORK_DIR" "$SCRIPT_DIR/ApiContractCheck.java"
echo "   所有 API 签名由编译器确认"

echo
echo "两处静态检查均通过。"
echo "注意：这只能证明「编译得过」和「签名对得上」，运行时行为仍需在真实 XWiki 上冒烟测试。"
