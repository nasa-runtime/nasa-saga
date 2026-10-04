# HTTP/gRPC 通信执行组件

[中文](README.md) | [English](README.en.md) | [项目说明](../../README.md)

`SagaHttpTransport`、`SagaGrpcChannels` 与 `SagaGrpcServers` 提供统一的通信执行配置，默认借用
nasa-core 默认时间轮的虚拟线程任务执行器。该方式让通信任务使用宿主的共享执行资源；组件保留各自的
HTTP client、channel 或 server，连接与生命周期不会因此合并。

## 配置与入口

`io.github.nasaruntime.saga.rc.SagaExecutionConfig` 是不可变构造配置，不自动读取 YAML 或系统属性：

```java
import io.github.nasaruntime.saga.rc.SagaExecutionConfig;

var shared = new SagaExecutionConfig();
var libraryDefaults = new SagaExecutionConfig(false);
```

`useTimingWheelExecutor` 默认 `true`，`SagaExecutionConfig.DEFAULT` 与无配置参数的入口采用相同策略。
创建配置本身不启动时间轮；传入配置并构造通信组件时才选择执行器。配置不能动态替换既有组件的执行器。

| 入口 | 共享执行器的用途 | 配置位置 |
| --- | --- | --- |
| `new SagaHttpTransport(...)` | `HttpClient.Builder.executor` 控制的异步及依赖任务 | 六参数构造器的最后一个参数 |
| `SagaGrpcChannels.openMtls(target, material, config)` | channel 应用回调与 `offloadExecutor` 阻塞辅助任务 | 文件 `SagaMtlsConfig`、内存 `SagaMtlsMaterial` 两种重载均支持 |
| `SagaGrpcServers.participantBuilder(..., config)` | server 服务回调 | 端口、`InetSocketAddress` 两种入口的最后一个参数 |

启用时，SDK 调用 `TimingWheel.of().start()`，再取得 `getVirtualExecutor()`；`start()` 幂等，
多个组件共享同一默认 Runner 的执行器。关闭接入时，SDK 不访问或启动时间轮，通信库采用自身默认执行器。
`false` 不移除 `nasa-core:1.0.4` 传递依赖，也不停止其它组件已经启动的时间轮。

完整装配示例见 [接入指南](../getting-started.md#通信执行配置)。返回的 server builder 仍由宿主装配和启动；
仅构造 builder 就可能启动时间轮，但不会绑定监听端口。宿主后续调用 builder 的执行器设置方法会覆盖这里的选择。

## 执行范围与业务边界

通信任务直接提交给标准执行器，不进入时间轮槽位，不经过 `Action` 的上下文或异常包装。
该接入不负责重试、延迟调度、租约续期或数据库扫描；可靠恢复仍依靠持久记录及宿主扫描器。

Netty I/O 线程和协议计时由通信库管理；外部创建并传入的 `ManagedChannel`、宿主 HTTP listener 和
业务扫描器不受此配置控制。gRPC blocking stub 仍可能在调用线程执行相关回调，不能据此假定所有代码都运行在虚拟线程。

共享虚拟线程执行器按任务创建虚拟线程，不提供业务并发或数据库连接等待上限。宿主须限制在途工作，
并保持身份、gRPC Context、traceparent 与本地事务的正确绑定；已开启的 JDBC 事务不能跨线程搬移。
认证、COMMIT → ACK、lease/fencing 与重投身份的要求不随执行器选择变化。时间轮已启动也不代表 Ready、
有效 capability、可用数据库或远端可达。

## 资源归属与停机

时间轮拥有共享执行器；Saga 通信组件仅借用。默认时间轮包含非守护调度线程，`main` 返回或最后一个
transport 关闭后 JVM 仍可能继续运行。应用须主动协调退出，初始化中途失败也要沿同一资源释放路径处理。

1. 关闭业务准入、续租与扫描提交，等待在途本地事务，按原身份尽力排空已提交结果。
2. 关闭 HTTP transport、gRPC channel/server，并等待依赖它们的工作结束；这些操作不停止共享时间轮。
3. 默认时间轮的全部使用者结束后，由宿主调用 `TimingWheel.of().stop()`，或纳入宿主统一的 nasa-core 停机流程。

`SagaHttpTransport.close()` 会取消未完成请求，不能用关闭动作代替排空。gRPC 的关闭请求也不等于已经终止，
宿主须在总停机预算内等待结束。中断或断连不能证明远端未提交，原持久身份与不确定结果必须保留。

组件构造与全局停机须由宿主串行管理。构造时若观察到时间轮停机或执行器已关闭，会明确拒绝；启动异常向调用方传播，
不会自动退回通信库默认执行器。取得执行器之后再发生停机仍可能导致后续任务拒绝，构造检查不构成资源存活租约。

不要在组件存活期间单独停止并重启时间轮：重启会创建新执行器，既有组件仍持有旧实例。先关闭旧通信组件，再重新
构造以取得当前执行器；发送请求不会触发时间轮自动重启。详细顺序见 [运维指南](../operations.md#有界停机)。

## 观测

记录组件构造时的执行策略，分别观测在途请求、超时、任务拒绝、连接池等待与停机各阶段耗时。
`TimingWheel.isStarted()` 仅表示默认时间轮的启动状态，不能替代通信可用性或业务准入检查。
SDK 不自动导出执行器指标或健康端点；宿主应将生命周期状态与业务投递状态分别展示，避免将共享资源启动当作服务就绪。
