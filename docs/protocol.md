# 协议与兼容边界

[中文](protocol.md) | [English](protocol.en.md)

## 权威来源

Java 协议源为 [saga_transport.proto](https://github.com/nasa-runtime/nasa-saga/blob/master/src/main/proto/saga_transport.proto) 与
[saga_orchestrator.proto](https://github.com/nasa-runtime/nasa-saga/blob/master/src/main/proto/saga_orchestrator.proto)。wire package、字段编号、RPC 与 enum 值同
Rust `nasaga-runtime-core/proto`；Java 生成选项仅决定本地类型布局。兼容判断以这些公开合同为准，不能仅凭两侧版本号相同。
手写 record 位于 `io.github.nasaruntime.saga.rc`，生成类位于各 proto 声明的 Java package。

| 能力 | Java API | 确认边界 |
| --- | --- | --- |
| start/get/query/audit | `SagaControlPlane` | 读取 Rust 权威事实，不建立 Java 全局状态机 |
| 原始 gRPC 管理与 Definition Registry | `RustSagaClient` | 需要相应独立授权 |
| command 入站 | HTTP ingress / gRPC service | 本地业务与控制事实提交后 ACK |
| result 出站 | HTTP publisher / gRPC transport client | 远端匹配的 Committed/Duplicate 才允许前移 |
| capability 注册 | HTTP/gRPC registrar | 完整合同与有效租期成立才授予 command 准入 |

Java SDK 不提供全局 Orchestrator、Admin、Registry 服务端或 Kafka/Redis Streams connector。

## 稳定身份与原始正文

Saga/trigger/business key 标识发起意图；effect 标识业务效果；command 标识该动作 attempt，网络重投不能更换；
result event 由命令身份派生。不同 attempt 可以指向同一效果，重放必须依据持久裁决，不能重复业务效果。

start 的 `input` 与 `payload` 互斥。`SagaPayload` 保存 `content_type`、`schema_id` 与原始 bytes；
HTTP 的 `payload.body` 使用数字数组，gRPC 使用 bytes。可靠 start 保存最终发送正文与本地完整性摘要，
本地 SHA-256 不等于 Rust 的语义 `request_digest`。

协议 DTO 在绑定 Java 类型前拒绝重复字段、隐式字符串/布尔/数字转换、非整数协议字段和根 null。
只接受无 BOM 的合法 UTF-8 与 Unicode 标量。业务 JSON 对象的重复键保持后值覆盖语义，但被覆盖的值仍须满足
编码、数值与深度边界；每个独立 JSON 正文最多 127 层容器。非 JSON 媒体按其 schema 保留 bytes。
这些校验不承诺 Java/Rust 浮点 canonical 文本完全一致。

## 成功收据与不确定结果

start 的成功收据必须含合法 request digest，且 tenant、Saga、workflow、business key、definition version 与冻结请求一致；
指定 expected definition digest 时也必须匹配。字段缺失、范围漂移或响应损坏不能作为成功，亦不能推出远端未提交。

HTTP/gRPC 的 command/result 四值收据区分提交、重复、可重试与确定拒绝。认证主体必须与步骤 owner 或受信 Orchestrator
关系相符；认证通过不能代替业务证据或事务资格。读取 API 同样核对身份、过滤范围与分页，空数组和缺失集合不是同一含义。

HTTP capability 收据包含正的 catalog generation；当前 gRPC protobuf 不携带该字段，Java 的 0 表示未提供，
不能把它当作 Catalog 权威。两者都核对完整摘要、route generation 与租期；摘要包含 `replica_identity`，绑定本次登记的
副本与路由。HTTP 收据没有独立的 registration ID 字段；gRPC 还会显式核对响应的 `registration_id`。协议字段出现
未知 enum 或不支持的状态时明确拒绝，不默认解释为成功。

## HTTP 路径与分页

HTTP `get` 与 `audit` 将 `tenant_id`、`saga_id` 放入路径，仅接受 URI unreserved ASCII 字符
`A-Z`、`a-z`、`0-9`、`.`、`_`、`-`、`~`，不会替调用方转义或规范化身份。start/query 的 JSON 字段与 gRPC
不受这一 HTTP 路径限制；需要后续通过 HTTP `get/audit` 访问时，应在创建身份时采用共同的字符范围。

`query` 使用 `POST /instances/query`。`audit` 使用 `GET /instances/{tenant}/{saga}/audit`，有分页条件时将
`page_size`、`page_token` 放入 JSON 正文；代理必须原样保留该 GET 正文，不能删除或改成 query 参数。
实际 path 与原 body 共同参与 HMAC 签名。上述路由均相对于配置的 Saga base URI。

## 结果恢复的协调端条件

仅能交换相同 protobuf/JSON 字段，不代表部署的协调端一定支持 command 路由关闭后的独立结果接收。
该恢复路径要求 Rust Orchestrator 独立核对结果资格，并在事务等待后继续复验 Catalog、冻结定义、owner 信任、
证据期限、安全发布代际与生命周期。若协调端随 command 路由一起关闭结果入口，Java 持续重投也不能单独完成恢复。
Java 的逐事件业务证据、稳定身份和租约检查仍必须成立；SDK 不会改变协调端的准入策略。

## 类型与数据演进

公开 record 的 Java 包名与签名属于源码及二进制 API。MyBatis 的参数和返回类型采用同一 `rc` record，应用应整体重新
编译并使用匹配的 SDK，不能把旧类文件和当前 record 混装。包布局本身不改变 JSON/protobuf 字段、事件身份派生或表结构。

协议字段、枚举、摘要算法、SQL 列与裁决语义变更必须逐项考虑存量请求、未知提交、历史 Outbox、原始输入和迟到结果。
初始化 SQL 不提供任意历史数据迁移；需要新增证据时只能依据可信原始事实处理。配置与运行处置见 [运维指南](operations.md)。
