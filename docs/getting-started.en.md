# Getting started

[中文](getting-started.md) | [English](getting-started.en.md)

## Choose a role

| Need | Java entry point | Local persistence |
| --- | --- | --- |
| Direct start, query, or audit | HTTP/gRPC `SagaControlPlane` | The SDK creates no database |
| Durable start after a business commit | `ReliableSagaStarter`, `SagaStartIntentDispatcher` | Business facts and start intent in one transaction |
| Execute workflow steps | `@Saga`, `SagaStep`, `SagaParticipantTransaction` | Business facts, gate, Inbox, and result Outbox in one transaction |

Before starting, Rust needs an active workflow, matching definition version, complete participant capabilities, trusted
callers, and API permissions. Java local tables do not replace Rust Catalog. Both HTTP and gRPC support clients and
participants. The Java SDK supplies neither Kafka/Redis Streams connectors nor automatic listeners, database pools, or scanners.
Constructing HTTP/gRPC communication components starts the nasa-core timing wheel as needed and shares its virtual-thread
task executor by default.

## Dependencies and packages

Use JDK 21+ and Maven 3.6.3+:

```xml
<dependency>
    <groupId>io.github.nasa-runtime</groupId>
    <artifactId>nasa-saga</artifactId>
    <version>1.0.0</version>
</dependency>
```

For persistence adapters, also declare MyBatis and the selected JDBC driver; the SDK's MyBatis dependency is optional.
Applications own pools and transactions. The main JAR contains generated protocol types, annotation-processor registration,
and SQL resources.
`nasa-core:1.0.4` is a transitive dependency for the default execution policy; no separate declaration is required.

| Package | Contents |
| --- | --- |
| `io.github.nasaruntime.saga` | Annotations, interfaces, control planes, transport, dispatchers, exceptions |
| `io.github.nasaruntime.saga.rc` | Public records, including request, context, outcomes, start intent, and result Outbox |
| `io.github.nasaruntime.saga.mybatis` | Mappers, stores, SQL provider, dialects, state enums |
| `io.github.nasaruntime.saga.proto.transport.v1` | Generated command/result protobuf services and clients |
| `io.github.nasaruntime.saga.proto.orchestrator.v1` | Generated orchestration and management protocol APIs |

A root-package wildcard import does not include `.rc`. Mappers and business code use the same record types.

## Communication execution configuration

`io.github.nasaruntime.saga.rc.SagaExecutionConfig` is constructor configuration; it does not automatically read YAML
or system properties. `new SagaExecutionConfig()` sets `useTimingWheelExecutor` to `true`, as do existing entry points
without that parameter. Enabling it calls the default wheel's idempotent `start()` and borrows its virtual-thread executor.

The following fragment assumes the application supplies addresses, authentication, certificates, and timeouts.
Pass the same configuration to communication components that should use the same policy:

```java
var execution = new io.github.nasaruntime.saga.rc.SagaExecutionConfig(false);
var http = new SagaHttpTransport(baseUri, producer, authenticator, requestTimeout, responseLimitBytes, execution);
var channel = SagaGrpcChannels.openMtls(target, material, execution);
var serverBuilder = SagaGrpcServers.participantBuilder(address, clientCa, serverCertificate, serverKey, execution);
```

`false` retains the HTTP/gRPC default executors without initializing or starting the wheel; use `true` to share the
wheel's virtual-thread executor. Both file and in-memory gRPC credentials and both server port and address overloads
support this setting. Configuration is fixed at construction and does not alter existing components or executors
of externally supplied `ManagedChannel` instances.

Applications close their communication components but must not stop the shared wheel when closing one component.
On application shutdown, drain and close HTTP/gRPC components before stopping the default wheel. Do not restart the wheel
independently while its communication components remain alive. Disabling the setting does not remove the Maven dependency.
The default wheel's non-daemon scheduling thread keeps the JVM alive after `main` returns, so standalone applications also
need explicit shutdown. See the [communication component guide](execution/README.en.md) and
[lifecycle requirements](operations.en.md#timing-wheel-executor-lifecycle).

## HTTP direct start

The following complete `StartSaga.java` accepts the actual Saga base URL, producer, tenant, Saga ID, trigger ID, and order ID.
It uses workflow `order_checkout`, definition version 1. Deployment supplies `SAGA_CLIENT_KEY` as 64 lowercase hexadecimal
characters matching the producer's Rust API credential. Use HTTPS or an equivalently protected trusted link; HMAC does not
encrypt payloads.

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

The base URL includes the actual service context and Saga prefix. The SDK appends `/instances`; do not include it twice.
For subsequent HTTP `get/audit`, tenant and Saga identities must use URI unreserved ASCII. See
[HTTP paths and pagination](protocol.en.md#http-paths-and-pagination) for the exact range and pagination transport.
Grant ordinary callers only the required tenant and `start/read/audit` permissions. COMMITTED/DUPLICATE confirms that Rust
accepted the start; it does not mean every step completed. Use `get` for the authoritative workflow state.

After timeout, disconnect, or malformed response, keep the same Saga, trigger, business key, deadline, and payload when
retrying. The transport creates a fresh nonce and signature for each attempt. This example has no durable local queue;
use reliable start when a committed business write must survive process loss before remote confirmation.

## gRPC control plane

gRPC uses the same request and control-plane contracts. This complete `GrpcStart.java` reads a protected credential JSON
containing `ca_certificate_pem`, `identity_certificate_pem`, `identity_private_key_pem`, and optional `domain_name`.
The server must trust and authorize the certificate principal, and the client must verify the server name.

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

Long-running services should reuse bounded channels, then close them after stopping admission and necessary draining.
`RustSagaClient` also exposes generated management and registry calls, which require separate administrative authorization.

HTTP supports `after_saga_id`; gRPC explicitly rejects that field. Prefer shared opaque page tokens. Reuse a token only with
the same query scope; do not parse, trim, or re-encode it.

## Reliable start

When building the host's `SqlSessionFactory`, register the SDK mapper through MyBatis XML or
`Configuration.addMapper(SagaMybatisMapper.class)`. The SDK does not scan or automatically register the host's MyBatis configuration.

1. Initialize `db/saga-start-intent-mysql.sql` or `db/saga-start-intent-postgresql.sql` for the selected database.
2. In one non-autocommit MyBatis session, write business facts, create `SagaMybatisStore(session, dialect)`, pass that store
   to `SagaMybatisStartIntentAppender`, and call `ReliableSagaStarter.submit`.
3. Report local acceptance only after the outer transaction commits. `SagaReliableStartReceipt` is not Rust confirmation.
4. Run an independent scanner using `SagaMybatisStartIntentLeaseStore` and `SagaStartIntentDispatcher` against the same
   transaction domain as the business writes.
5. Separate short claim/settle transactions from network I/O. Settle using the original owner/token; timeout or lost authority
   leaves the original intent for a later scan.

Repeated business submissions should read the original intent through the business unique key. Calling `submit` again does
not automatically reuse its randomly generated local intent ID. Deterministic rejection or local contract damage enters
NEEDS_ATTENTION and cannot be treated as delivery success.

See [Participants](participant.en.md), [Operations](operations.en.md), and [Protocol](protocol.en.md) for the remaining contracts.
