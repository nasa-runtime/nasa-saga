# 运行、观测与恢复

[中文](operations.md) | [English](operations.en.md)

## 配置责任

SDK 通过构造参数接收配置，不定义自动加载的 application.yml。HTTP/gRPC 通信组件默认按需启动并借用
nasa-core 默认时间轮的虚拟线程执行器；宿主负责其它业务线程、数据库连接池、扫描调度、健康端点及统一停机。
Rust 侧角色、Catalog、身份信任及 transport 配置由其受管 Application 持有。

| 配置边界 | 要求 |
| --- | --- |
| HTTP base URI、producer、HMAC | 与实际 listener path 和 Rust 受信调用者一致；key 不写日志 |
| gRPC target、CA、client certificate/key | 双向信任、服务端名称校验与 principal 授权同时成立 |
| `SagaExecutionConfig.useTimingWheelExecutor` | 默认 true；启用时自动启动并借用默认时间轮虚拟线程执行器，false 保留通信库默认值 |
| 请求 timeout、正文限制、并发/队列上限 | 有限，覆盖完整响应；SDK 不替宿主选择 listener 容量 |
| JDBC datasource、MyBatis session、SQL 方言 | 同一原子链使用同一数据源与事务；不能跨连接拼接提交 |
| owner、lease、fencing token | 每次领取有独立 token，结算只使用该次 token |
| 扫描间隔、退避、批量 | 有界；持久扫描承担恢复，不能只依赖内存唤醒 |

lease 必须覆盖完整网络 timeout，发送前还须扣除领取和证据核验耗时。数据库与应用保持时钟同步，绝对时间和
单调预算取更严格边界。不能为了清空积压而跳过 token、无限延长 lease 或取消证据核验。

## 时间轮执行器的生命周期

执行配置在 `SagaHttpTransport`、`SagaGrpcChannels.openMtls` 与 `SagaGrpcServers.participantBuilder`
构造时生效。`true` 调用 `TimingWheel.of().start()` 并借用 `getVirtualExecutor()`，多次初始化共享同一个
默认 Runner；不会为每个请求创建 Runner。`false` 不访问时间轮，不初始化资源或注册其停机动作。
该开关不自动移除 nasa-core Maven 依赖，也不改变其它组件已经启动的时间轮。

默认时间轮使用非守护调度线程，独立程序仅结束 main 或关闭最后一个 transport 不会使 JVM 退出。
应用必须显式执行下述统一停机流程；构造失败后的清理也由拥有进程生命周期的宿主负责。

HTTP 将共享执行器交给 HttpClient；gRPC channel 的应用回调与 `offloadExecutor` 同时使用它，server
只设置服务回调执行器。Netty I/O、协议定时器、外部 channel、宿主 HTTP listener 与业务扫描器保留各自的调度。
任务直接提交到标准执行器，没有轮槽延迟，也不经过 Action 的异常和上下文包装。

虚拟线程按任务创建，不限制数据库等待者或在途请求数。宿主仍须按连接容量限制业务并发，保持认证、
gRPC Context、traceparent 和本地事务绑定；不能在开启事务后随意跨线程执行 JDBC。

Saga 不拥有共享执行器，关闭 HTTP client、channel 或 server 都不会停止时间轮。宿主退出时先停止新业务与扫描提交，
等待在途事务及结果投递收口，关闭并等待通信组件结束；所有依赖默认时间轮的组件结束后，再调用
`TimingWheel.of().stop()`，或交给宿主统一管理的 nasa-core 停机流程。构造组件与全局停机须串行管理。
自动启动完成后若观察到并发停机或执行器关闭，SDK 拒绝构造；随后发生停机时，新提交仍可能被执行器拒绝，不能静默切回默认池。

不支持在通信组件存活期间独立 stop/start 时间轮：重新启动会替换任务执行器，旧组件仍绑定旧执行器。
完成原组件关闭后重新构建，才能借用当前执行器；请求执行期间不会自动启动时间轮。
构造启用配置的 server builder 会按需启动时间轮，但实际 listener、capability 续约和持久扫描仍由宿主启动。
各入口的执行范围与配置重载见 [通信执行组件](execution/README.md)。

## 状态与观测

start intent 与 result Outbox 共享等待、领取和隔离状态，但使用不同的成功状态：

| 状态 | 含义 | 处置 |
| --- | --- | --- |
| `PENDING` | 等待可投递时间或重试 | 观察积压、最老记录年龄和 `next_attempt_at_ms` |
| `IN_FLIGHT` | worker 持有有界 lease | 到期由扫描器竞争新 token；旧 worker 不得结算 |
| `COMMITTED` / `DUPLICATE` | start intent 已取得匹配的提交或重复收据 | 不等于全局流程已完成，保留所需发起证据 |
| `DELIVERED` | result Outbox 已取得匹配的 Committed/Duplicate | 表示本次结果投递完成，保留业务与提交证据 |
| `NEEDS_ATTENTION` | 确定合同拒绝或本地证据不可用 | 不自动重投；核对原因码及原始事实 |

分别监控投递积压、最老年龄、过期领取、失权结算、错误码分布、capability 剩余期限、Ready、恢复态与停机结果。
SDK 不自动导出 metrics endpoint；宿主可从持久记录和 dispatcher 结果形成低基数指标。Saga/event 身份用于受控日志
关联，不进入指标 label；不记录 payload、私钥、HMAC key 或原始认证头。

同时记录通信组件的执行策略，观测在途请求、超时、任务拒绝、数据库连接等待及各停机阶段耗时。
`TimingWheel.isStarted()` 仅表示默认时间轮的启动状态，不证明业务 Ready、capability 有效或远端可达；
共享执行器的存活也不能代替应用并发限制。

Rust 的权威实例状态必须独立查询。`COMPLETED`、`COMPENSATED`、`MANUAL_INTERVENTION` 与
`MANUALLY_CLOSED` 有不同业务含义；人工关闭并不证明正向效果成功或已被补偿。

## 错误与重试

| 原因或收据 | 处理 |
| --- | --- |
| 匹配的 `Committed` / `Duplicate` | 持有当前 token 才允许结算；start intent 记录对应收据状态，result Outbox 记录 DELIVERED |
| HTTP 408/425/429/5xx、gRPC 暂不可用、超时、断连 | 原身份退避重投；可能已在远端提交 |
| 认证、授权、身份冲突、合同确定拒绝 | 核对凭据、权限与冻结请求，不创建新身份冒充恢复 |
| `local_request_contract_invalid` | start intent 本地合同损坏，保留原正文并隔离 |
| `local_result_contract_invalid` | result 记录或 envelope 合同损坏，保留原正文并隔离 |
| `local_result_evidence_unavailable` | 当前结果缺少业务语义证据，隔离该事件，不发送 |
| `returned_snapshot_mismatch` | 成功收据身份与冻结 start intent 不一致，隔离并核对远端事实 |

SQL 暂不可用、连接失败等不能归因为消息损坏；领取事务应回滚。重试耗尽不能把未知外部效果当作无效果。
HTTP 重投保留 event/command 身份与正文，但每次使用新的签名 nonce。

## 保护态与结果恢复

command 准入和已提交 result 发送分别决策。业务来源不成立时关闭 command/Ready 与 capability；最小结果提交
证据成立后可仅恢复 Outbox，再逐事件证明成功、补偿、拒绝或屏障的语义。无证据的成功事件不能因为进程处于恢复态
而获得豁免。结果排空不自动开放新业务。

Rust command 路由缺席时，结果资格仍需当前 Catalog 与已发布定义相容、owner 信任完整、证据未到期且生命周期可用。
每个请求冻结期限、撤销身份、安全发布代际与合同摘要。SQL 等待后失权整笔回滚，返回可重投结论；新确认不复活旧请求。
任何成功配置发布都可能要求新的资格确认，材料恢复相同字节也不例外。此限制可能暂时影响可用性。

这不保证撤销已发出的 COMMIT，也不能阻止有权限的外部 SQL 在只读证据快照结束后篡改业务数据。
维护必须遵守数据库权限与停写边界；跨系统外部效果需要稳定幂等键、可信查询或人工裁决。

## 人工处置与数据保留

先暂停相关业务写入并保存实例、业务事实、Inbox、gate、Outbox 和审计，再按租户、定义摘要、step、phase、effect、
command/event 身份及首次输入绑定核对。不能仅凭当前业务行回填原始成功，也不能删除孤立事实或改写结果来获得 Ready。
无法唯一证明的记录保持隔离，按业务政策裁决；恢复后重新执行完整准入检查。

已提交的 Inbox 和已 DELIVERED 的 result Outbox 仍可能是重复命令、启动来源检查和迟到结果的证据，不能按投递状态立即删除。
保留期须覆盖业务审计、重投、补偿与恢复窗口，并与 Rust definition 退休条件协调。

## 有界停机

1. 永久撤销新 command/Ready 与续租准入，停止接收新连接；迟到续租响应不能重新开放。
2. 取消尚未执行的排队工作，等待已开始的本地事务提交或回滚；保留其依赖的数据源。
3. 按原身份、lease 和 fencing 尽力排空已提交 Outbox，再关闭并等待 HTTP/gRPC 通信组件结束，然后关闭数据库连接池。
4. 默认时间轮的全部依赖组件结束后，停止时间轮及其共享任务执行器。
5. 所有阶段共享同一停机预算；耗尽时记录未完成阶段与未知效果，保留持久记录供重启恢复。

关闭 socket 或中断线程不证明事务回滚。不得为及时退出而把不确定投递标记为成功；外部效果仍依赖自身幂等或 resolution。
