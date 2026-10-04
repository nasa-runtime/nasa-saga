# HTTP/gRPC communication execution

[中文](README.md) | [English](README.en.md) | [Project overview](../../README.en.md)

`SagaHttpTransport`, `SagaGrpcChannels`, and `SagaGrpcServers` share one execution configuration and borrow the
nasa-core default timing wheel's virtual-thread task executor by default. This places communication tasks on the host's
shared execution resources. Each component retains its own HTTP client, channel, or server, with separate connections
and lifecycles.

## Configuration and entry points

`io.github.nasaruntime.saga.rc.SagaExecutionConfig` is immutable constructor configuration. It does not load YAML or system properties:

```java
import io.github.nasaruntime.saga.rc.SagaExecutionConfig;

var shared = new SagaExecutionConfig();
var libraryDefaults = new SagaExecutionConfig(false);
```

`useTimingWheelExecutor` defaults to `true`. `SagaExecutionConfig.DEFAULT` and entry points without that parameter
use the same policy. Creating configuration does not start the wheel; constructing a communication component with it
selects the executor. Configuration cannot dynamically replace an existing component's executor.

| Entry point | Shared executor scope | Configuration parameter |
| --- | --- | --- |
| `new SagaHttpTransport(...)` | Asynchronous and dependent tasks governed by `HttpClient.Builder.executor` | Last parameter of the six-argument constructor |
| `SagaGrpcChannels.openMtls(target, material, config)` | Channel application callbacks and blocking auxiliary work through `offloadExecutor` | Both file-based `SagaMtlsConfig` and in-memory `SagaMtlsMaterial` overloads |
| `SagaGrpcServers.participantBuilder(..., config)` | Server service callbacks | Last parameter of both the port and `InetSocketAddress` overloads |

When enabled, the SDK calls `TimingWheel.of().start()` before obtaining `getVirtualExecutor()`. Startup is idempotent;
components share the same default Runner's executor. When disabled, the SDK does not access or start the wheel and retains
the communication libraries' defaults. `false` neither removes the transitive `nasa-core:1.0.4` dependency nor stops a wheel
already started by another component.

See [configuration examples](../getting-started.en.md#communication-execution-configuration). The host configures and
starts the returned server builder. Constructing it may start the wheel but does not bind a listening port. Subsequent
executor settings applied by the host to that builder override this selection.

## Execution scope and business boundaries

Tasks go directly to the standard executor, without wheel slots or `Action` context and exception wrappers. This
integration does not schedule retries, delayed work, lease renewal, or database scans. Durable records and host scanners
remain responsible for recovery.

Communication libraries retain Netty I/O threads and protocol timers. Externally created `ManagedChannel` instances,
host HTTP listeners, and business scanners are outside this setting's scope. A gRPC blocking stub may still execute
related callbacks on the calling thread; the setting does not place all code on virtual threads.

The shared executor creates a virtual thread per task and does not bound business concurrency or database waiters.
Hosts must limit active work and preserve identity, gRPC Context, traceparent, and local transaction bindings. Do not move
an open JDBC transaction across threads. Authentication, COMMIT → ACK ordering, lease/fencing, and retry identity
requirements apply with either executor policy. A started wheel does not establish Ready, valid capability, database
availability, or remote connectivity.

## Ownership and shutdown

The timing wheel owns the shared executor; Saga components borrow it. The default wheel includes a non-daemon scheduling
thread, so returning from `main` or closing the last transport may leave the JVM running. Applications must coordinate
shutdown explicitly, including cleanup when initialization fails partway through.

1. Close business admission, renewal, and scan submissions; await active local transactions and drain committed results using their original identities.
2. Close HTTP transports and gRPC channels/servers, then await dependent work. Closing these components does not stop the shared wheel.
3. After all users of the default wheel finish, the host calls `TimingWheel.of().stop()` or includes it in its coordinated nasa-core shutdown flow.

`SagaHttpTransport.close()` cancels unfinished requests; closure cannot substitute for draining. A gRPC shutdown request
also does not establish termination: the host must await it within the overall shutdown budget. Interruption and
disconnects do not prove that the remote side did not commit. Preserve durable identities and uncertain outcomes.

The host must serialize component construction with global shutdown. Construction rejects an observed stopped wheel or
closed executor after startup; startup failures propagate. There is no automatic fallback to library defaults. Shutdown
after acquiring the executor may still reject later submissions; construction checks do not grant a resource lifetime lease.

Do not independently stop and restart the wheel while components remain alive: restart creates a new executor, while
existing components retain the old instance. Close the old components and rebuild them to obtain the current executor.
Sending requests does not automatically restart the wheel. See [bounded shutdown](../operations.en.md#bounded-shutdown).

## Observation

Record the execution policy at component construction and observe active requests, timeouts, rejected tasks, connection
pool waits, and elapsed time in each shutdown phase. `TimingWheel.isStarted()` reports only the default wheel's startup
state; it does not replace communication availability or business admission checks. The SDK exports no automatic executor
metrics or health endpoint. Display lifecycle and business delivery states separately instead of treating started shared
resources as service readiness.
