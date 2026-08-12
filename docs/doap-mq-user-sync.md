# 知脉(DOAP)用户 MQ 同步接入手册

本文档说明如何接入知脉(DOAP)用户数据 MQ 消费同步：消费 RocketMQ 集群 `TOPIC_DOAP_USER` 上的用户消息，仅同步**外包**用户到 XWiki。

- 模块：`xwiki-platform-core/xwiki-platform-oa-sync`
- MQ：Apache RocketMQ 4.7.1（`rocketmq-client`）
- 功能：外包用户建号 / 加入统一权限组 / 条件禁用

## 1. 功能概述

订阅 topic `TOPIC_DOAP_USER`，tag：`TAG_USER_CREATE` / `TAG_USER_UPDATE` / `TAG_USER_DELETE`（三个 tag 均订阅，处理逻辑由消息字段驱动，tag 只用于路由与日志）。

处理规则：

- `type == "outsourcing"` 才处理，其余消息直接丢弃；
- 工号 `staffId` 即 XWiki 用户名，用户不存在则新建；
- 所有外包用户加入权限组 **「开发中心科技外包组」**（组不存在自动创建，成员幂等添加）；
- `isDeleted == true` 或 `status == "dismission"` → 禁用用户；否则保持原状态（**不会**重新启用）。

## 2. 编译

```bash
cd /Users/noaharno/programer/sourcecode/xwiki_parent/xwiki-platform
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml \
  -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-api,\
xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default \
  -am -DskipTests=true -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true install
```

产物：`xwiki-platform-oa-sync-api-18.1.0.jar`、`xwiki-platform-oa-sync-default-18.1.0.jar`。

> 注意：仓库自带的 Develocity Maven 扩展（`.mvn/extensions.xml`）在部分本机 Maven 环境下会初始化报错，可临时将该文件改名后构建，构建完再改回。

## 3. 部署到 Tomcat

将以下 jar 拷贝到 `.../apache-tomcat-11.0.22/webapps/xwiki/WEB-INF/lib/`：

**模块 jar**

- `xwiki-platform-oa-sync-api-18.1.0.jar`（若之前已部署过 OA 同步模块可跳过）
- `xwiki-platform-oa-sync-default-18.1.0.jar`（本次更新，覆盖旧版）

**RocketMQ 4.7.1 相关 jar（新增，来源为本地 `~/.m2/repository`）**

| jar | 路径 |
|---|---|
| rocketmq-client-4.7.1.jar | `org/apache/rocketmq/rocketmq-client/4.7.1/` |
| rocketmq-common-4.7.1.jar | `org/apache/rocketmq/rocketmq-common/4.7.1/` |
| rocketmq-remoting-4.7.1.jar | `org/apache/rocketmq/rocketmq-remoting/4.7.1/` |
| rocketmq-logging-4.7.1.jar | `org/apache/rocketmq/rocketmq-logging/4.7.1/` |
| fastjson-1.2.69.jar | `com/alibaba/fastjson/1.2.69/` |
| netty-all-4.0.42.Final.jar | `io/netty/netty-all/4.0.42.Final/` |
| netty-tcnative-boringssl-static-1.1.33.Fork26.jar（仅 TLS 需要） | `io/netty/netty-tcnative-boringssl-static/1.1.33.Fork26/` |

> `slf4j-api`、`commons-lang3`、`guava` 等 XWiki 已自带，无需重复拷贝。

重启 Tomcat：`shutdown.sh` → `startup.sh`。

## 4. 配置（`xwiki.cfg`）

```properties
# 是否启用知脉 MQ 消费（默认 false）
xwiki.doap-mq.enabled=true
# MQ 集群 namesrv 地址，多个用 ; 分隔
xwiki.doap-mq.name-server=10.x.x.x:9876;10.x.x.x:9876
# 消费组（同集群内唯一；多台 XWiki 共用同一组名 = 集群消费，一条消息只消费一次）
xwiki.doap-mq.consumer-group=GID_XWIKI_DOAP_USER
xwiki.doap-mq.topic=TOPIC_DOAP_USER
# 订阅的 tag，逗号分隔（默认三个全订）
xwiki.doap-mq.tags=TAG_USER_CREATE,TAG_USER_UPDATE,TAG_USER_DELETE
# 消费线程数（可选，默认 1/8）
xwiki.doap-mq.consume-thread-min=1
xwiki.doap-mq.consume-thread-max=8
```

## 5. 消息格式

所有 tag 的消息都是同一个 JSON 结构，处理只依赖以下字段（其余字段忽略）：

```json
{
  "type": "outsourcing",
  "staffId": "T10086",
  "isDeleted": false,
  "status": "active"
}
```

| 字段 | 说明 |
|---|---|
| `type` | 必须是 `outsourcing` 才处理，否则丢弃 |
| `staffId` | 工号 = XWiki 用户名 |
| `isDeleted` | `true`（或字符串 `"true"`）→ 禁用 |
| `status` | 值为 `dismission` → 禁用 |

## 6. 验证（日志）

启动成功：`DOAP MQ 消费已启动: group=[...] topic=[...] tags=[...] nameServer=[...]`

每条消息都会打印（含 tag）：`DOAP MQ 收到消息: topic=[TOPIC_DOAP_USER] tag=[TAG_USER_UPDATE] msgId=[...] reconsumeTimes=[0] body=[...]`

处理结果日志：`新建用户` / `创建权限组` / `禁用用户` / `用户状态无需变更` / `非外包用户消息，丢弃` / `缺少工号 staffId，丢弃`。

## 7. 注意事项

- **幂等**：重复消息不会重复建号 / 重复加组，可安全重试；
- **只禁用、不启用**：不满足禁用条件时保持用户原状态；
- **坏消息处理**：`staffId` 为空 → WARN 丢弃不重试；body 非 JSON / 处理器异常 → 返回重试（RocketMQ 默认重试 16 次后进死信队列）；
- **多节点部署**：多个 XWiki 节点共用同一 `consumer-group` 即集群消费；如需每个节点都消费，给不同节点配不同组名；
- **字段变更**：字段名、`outsourcing`、`dismission`、组名等常量集中在
  `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-api/src/main/java/org/xwiki/oa/sync/mq/DoapMqConstants.java`，
  后续按实际消息调整一处即可。
