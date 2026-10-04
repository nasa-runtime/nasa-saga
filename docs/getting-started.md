# 接入指南

[中文](getting-started.md) | [English](getting-started.en.md)

## 选择角色

| 需求 | Java 入口 | 本地持久化 |
| --- | --- | --- |
| 直接发起、查询或读取审计 | `SagaControlPlane` 的 HTTP/gRPC 实现 | SDK 不创建数据库 |
| 业务写入成功后必须持续发起流程 | `ReliableSagaStarter`、`SagaStartIntentDispatcher` | 业务事实与 start intent 同事务 |
| 执行流程步骤 | `@Saga`、`SagaStep`、`SagaParticipantTransaction` | 业务事实、gate、Inbox 与 result Outbox 同事务 |

运行前，Rust Orchestrator 必须具有已激活的 workflow、匹配的 definition version、完整参与方 capability、
可信调用者与 API 权限。Java 的本地数据表不能替代 Rust Catalog。HTTP 与 gRPC 均支持 client 和 participant；
Java SDK 不提供 Kafka/Redis Streams connector，也不会自动创建 Web listener、数据库连接池或后台扫描器。
SDK 构造 HTTP/gRPC 通信组件时默认按需启动 nasa-core 时间轮，并共享其虚拟线程任务执行器。

## 依赖与包名

构建与运行使用 JDK 21 或更高版本，Maven 要求 3.6.3+。SDK 产物以 Java 21 为兼容基线。
在应用 POM 中声明：

```xml
<dependency>
    <groupId>io.github.nasa-runtime</groupId>
    <artifactId>nasa-saga</artifactId>
    <version>1.0.0</version>
</dependency>
```

使用 MyBatis 持久化适配时，应用另行声明 MyBatis 和所选数据库 JDBC driver；SDK 的 MyBatis 依赖为 optional。
连接池和事务生命周期由应用拥有。SDK 主 JAR 同时包含协议生成类型、注解处理器注册与 SQL 资源。
`nasa-core:1.0.4` 是默认执行策略所需的传递依赖，无需单独声明。

| 包 | 内容 |
| --- | --- |
| `io.github.nasaruntime.saga` | 注解、业务接口、control plane、transport、dispatcher 和异常 |
| `io.github.nasaruntime.saga.rc` | 公开 record，包括 `SagaStartRequest`、`SagaContext`、`SagaOutcome`、`SagaStartIntent`、`SagaResultOutbox` |
| `io.github.nasaruntime.saga.mybatis` | Mapper、store、SQL provider、方言与状态枚举 |
| `io.github.nasaruntime.saga.proto.transport.v1` | command/result protobuf 与 generated service/client |
| `io.github.nasaruntime.saga.proto.orchestrator.v1` | Orchestrator 与管理 protobuf、generated service/client |

根包的通配 import 不包含 `rc` 子包。传给 Mapper 的 record 与业务代码使用同一套 `rc` 类型。

## 通信执行配置

`io.github.nasaruntime.saga.rc.SagaExecutionConfig` 是构造配置，不自动读取 YAML 或系统属性。
`new SagaExecutionConfig()` 的 `useTimingWheelExecutor` 为 `true`；旧有不带该参数的入口也采用同一默认值。
启用后自动调用默认时间轮的幂等 `start()`，并借用其虚拟线程执行器。

以下片段假设地址、认证器、证书材料与超时已经由应用提供；将同一配置传给需要统一管理的通信组件：

```java
var execution = new io.github.nasaruntime.saga.rc.SagaExecutionConfig(false);
var http = new SagaHttpTransport(baseUri, producer, authenticator, requestTimeout, responseLimitBytes, execution);
var channel = SagaGrpcChannels.openMtls(target, material, execution);
var serverBuilder = SagaGrpcServers.participantBuilder(address, clientCa, serverCertificate, serverKey, execution);
```

`false` 保留 HTTP/gRPC 默认执行器，不初始化或启动时间轮；改成 `true` 即共享时间轮虚拟线程执行器。
gRPC 的文件证书、内存证书入口以及 server 的端口、地址入口均支持该配置。配置在构造时确定，
不改变已存在的组件，也不替外部传入的 `ManagedChannel` 选择执行器。

应用拥有通信组件的关闭责任，但不能因关闭某个组件而停止共享时间轮。整个应用退出时应先排空并关闭
HTTP/gRPC 组件，最后停止默认时间轮；不得在组件存活期间单独重启时间轮。
默认时间轮的非守护调度线程不会随 `main` 返回而退出，独立应用也须显式停机。关闭开关不移除 Maven 依赖。
入口与执行范围见 [通信执行组件](execution/README.md)，具体资源归属见 [运维指南](operations.md#时间轮执行器的生命周期)。

## HTTP direct start

下面是可放入应用的完整 `StartSaga.java`。参数依次为包含实际 Saga base path 的 URL、producer、tenant、
Saga 身份、trigger 身份和订单身份；workflow 示例固定为 `order_checkout`、定义版本为 1。
`SAGA_CLIENT_KEY` 由部署注入，必须为 64 个小写十六进制字符，并与 Rust 为该 producer 配置的 API HMAC 一致。
网络应使用 HTTPS 或具备等效保护的受信链路；HMAC 本身不加密业务正文。

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nasaruntime.core.base.TimingWheel;
import io.github.nasaruntime.saga.HttpSagaControlPlane;
import io.github.nasaruntime.saga.SagaHttpMessageAuthenticator;
import io.github.nasaruntime.saga.SagaHttpTransport;
import io.github.nasaruntime.saga.rc.SagaStartRequest;
import java.net.URI;
import java.time.Duration;

public final class StartSaga {
    /**
     * 业务作用：以调用方固定的身份发起订单流程，并只输出已取得的远端收据。
     * @param args API 地址、producer、tenant、Saga、trigger、订单身份
     * @throws Exception 本地配置无效、请求被拒绝或未取得可靠远端收据；调用方须保留原身份处理。
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException("baseUri producer tenant sagaId triggerId orderId");
        }
        var input = new ObjectMapper().createObjectNode()
                .put("order_id", args[5]).put("quantity", 1);
        var request = new SagaStartRequest(args[2], args[3], "order_checkout", 1, null,
                args[5], args[4], input, null, null);
        var auth = new SagaHttpMessageAuthenticator(System.getenv("SAGA_CLIENT_KEY"), 30_000);
        try (var transport = new SagaHttpTransport(URI.create(args[0]), args[1], auth,
                Duration.ofSeconds(5), 1_048_576)) {
            var receipt = new HttpSagaControlPlane(transport).start(request);
            System.out.println(receipt.disposition() + " " + receipt.saga().sagaId()
                    + " " + receipt.saga().status());
        } finally {
            // 独立命令行程序拥有进程生命周期，通信结束后才停止默认时间轮。
            if (TimingWheel.isStarted()) {
                TimingWheel.of().stop();
            }
        }
    }
}
```

URL 应包含实际服务 context 与 Saga base path，SDK 在其后追加 `/instances`；不要把 `/instances` 重复写进 base URI。
需要通过 HTTP `get/audit` 读取时，tenant 与 Saga 身份仅使用 URI unreserved ASCII；具体范围与分页传输见
[HTTP 路径与分页](protocol.md#http-路径与分页)。
普通调用者只授予需要的 tenant 与 `start/read/audit` 权限。返回 `COMMITTED` 或 `DUPLICATE` 表示 Rust 已确认发起，
不表示所有步骤已完成；通过 `get` 读取权威快照判断业务终态。

超时、断连或响应损坏时，保留同一 Saga、trigger、business key、deadline 与业务正文重试，不能生成新身份掩盖未知结果。
HTTP transport 为每次尝试生成新的 nonce 和签名。示例没有本地可靠队列；需要业务提交后的持续恢复时采用下面的可靠模式。

## gRPC control plane

gRPC 使用同一 `SagaStartRequest` 和 `SagaControlPlane` 合同。以下 `GrpcStart.java` 从受保护凭据文件构造通道；
JSON 字段为 `ca_certificate_pem`、`identity_certificate_pem`、`identity_private_key_pem`、可选 `domain_name`。
server 必须信任本端证书 principal 并授予 API 权限，客户端必须核验服务端证书名称。

```java
import io.github.nasaruntime.saga.GrpcSagaControlPlane;
import io.github.nasaruntime.saga.RustSagaClient;
import io.github.nasaruntime.saga.SagaEnvelopeCodec;
import io.github.nasaruntime.saga.SagaGrpcChannels;
import io.github.nasaruntime.saga.SagaStartResult;
import io.github.nasaruntime.saga.rc.SagaMtlsMaterial;
import io.github.nasaruntime.saga.rc.SagaStartRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class GrpcStart {
    /**
     * 业务作用：使用受信 mTLS 身份发起流程，并在调用结束后释放本次通道。
     * @param target gRPC host:port 或 resolver target
     * @param credentialFile 受保护的 JSON 凭据路径
     * @param request 重投时保持相同的发起意图
     * @return 身份匹配的远端提交或重复收据。
     * @throws Exception 凭据、权限或远端收据不满足调用合同。
     */
    public static SagaStartResult start(String target, Path credentialFile,
                                       SagaStartRequest request) throws Exception {
        var material = SagaEnvelopeCodec.decodeStrict(Files.readAllBytes(credentialFile), SagaMtlsMaterial.class);
        var channel = SagaGrpcChannels.openMtls(target, material);
        try (var control = new GrpcSagaControlPlane(new RustSagaClient(channel, Duration.ofSeconds(5)))) {
            return control.start(request);
        }
    }
}
```

长期服务应复用有界 channel，停止接收新工作并完成必要排空后再关闭。`RustSagaClient` 也提供原始 generated
管理与 Definition Registry 调用；它们需要独立管理身份，不能复用普通业务调用者扩大权限。

HTTP 支持 `after_saga_id`，gRPC control plane 对该字段明确拒绝；优先使用两者共有的 opaque page token。
token 只能用于相同查询范围，不应解析、裁剪或重新编码。

## 可靠 start

构建宿主 `SqlSessionFactory` 时，先通过 MyBatis XML 或 `Configuration.addMapper(SagaMybatisMapper.class)`
注册 SDK mapper。SDK 不扫描或自动注册应用的 MyBatis 配置。

1. 按所选数据库初始化 `db/saga-start-intent-mysql.sql` 或 `db/saga-start-intent-postgresql.sql`。
2. 同一非自动提交 MyBatis `SqlSession` 内先写业务事实，再构造 `SagaMybatisStore(session, dialect)`，
   将该 store 传给 `SagaMybatisStartIntentAppender`，交由 `ReliableSagaStarter.submit` 追加 intent。
3. 外层事务明确提交后才对调用者表示本地已受理。`SagaReliableStartReceipt` 不代表 Rust 已确认。
4. 独立扫描器使用 `SagaMybatisStartIntentLeaseStore` 与 `SagaStartIntentDispatcher`。扫描和业务写入必须指向同一事务域。
5. 领取、网络和结算分成短事务与无事务网络调用；使用原 owner/token 结算，失权或超时保留原意图供后续扫描。

业务重复提交应先按业务唯一键读取原意图；不能依赖再次调用 `submit` 自动复用其随机生成的本地 intent ID。
下游拒绝或本地合同损坏进入 `NEEDS_ATTENTION`，自动扫描不能把它当作成功。恢复、租约与观测见 [运维指南](operations.md)。

participant 的完整装配与事务职责见 [参与方指南](participant.md)，协议字段与兼容规则见 [协议说明](protocol.md)。
