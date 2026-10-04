# Operations and recovery

[中文](operations.md) | [English](operations.en.md)

## Configuration ownership

The SDK accepts constructor parameters; it does not automatically load application.yml. HTTP/gRPC communication components
start and borrow the nasa-core default wheel's virtual-thread executor as needed. Hosts own other business threads,
database pools, scanning, health endpoints, and coordinated shutdown. Rust's managed Application owns its roles, Catalog,
identity trust, and transport configuration.

| Boundary | Requirement |
| --- | --- |
| HTTP base URI, producer, HMAC | Match the actual listener path and trusted caller; never log keys |
| gRPC target, CA, client certificate/key | Mutual trust, server-name verification, and principal authorization |
| `SagaExecutionConfig.useTimingWheelExecutor` | Defaults to true: start and borrow the default wheel's virtual-thread executor; false retains library defaults |
| Timeouts, body limits, concurrency, queues | Finite limits covering complete responses; host chooses listener capacity |
| JDBC datasource, session, SQL dialect | All atomic writes use one datasource and transaction |
| Owner, lease, fencing token | Distinct token per claim; settle only with that token |
| Scan interval, backoff, batch size | Bounded; persistent scans provide recovery beyond in-memory wakeups |

Reserve the entire network timeout within the lease after subtracting claim and evidence-check time. Synchronize database
and application clocks and respect both absolute and monotonic budgets. Do not drain a backlog by bypassing tokens,
extending leases indefinitely, or skipping evidence checks.

## Timing wheel executor lifecycle

Execution configuration takes effect when constructing `SagaHttpTransport`, `SagaGrpcChannels.openMtls`, or
`SagaGrpcServers.participantBuilder`. With `true`, the SDK calls `TimingWheel.of().start()` and borrows
`getVirtualExecutor()`. Repeated initialization shares the same default Runner; no Runner is created per request.
With `false`, the SDK does not access the wheel, initialize its resources, or register its shutdown action.
The setting neither removes the nasa-core Maven dependency nor changes a wheel already started by another component.

The default wheel uses a non-daemon scheduling thread. Returning from main or closing the last transport does not terminate
the JVM. The application must explicitly run coordinated shutdown, including cleanup after construction fails.

HTTP supplies the shared executor to HttpClient. gRPC channels use it for application callbacks and `offloadExecutor`;
servers use it for service callbacks. Netty I/O, protocol timers, external channels, host HTTP listeners, and business
scanners retain their scheduling. Tasks go directly to the standard executor, without wheel-slot delays or Action's
exception and context wrappers.

Virtual threads are created per task; they do not limit database waiters or active requests. Hosts must bound business
concurrency to match connection capacity and preserve authentication, gRPC Context, traceparent, and transaction bindings.
Do not move JDBC operations across threads after opening a transaction.

Saga does not own the shared executor. Closing an HTTP client, channel, or server leaves the wheel running. Stop new
business and scan submissions, await active transactions and result delivery, then close and await communication components.
After all components depending on the default wheel finish, call `TimingWheel.of().stop()` or use the host's coordinated
nasa-core shutdown flow. Serialize component construction with global shutdown. After automatic startup, observing concurrent
shutdown or a closed executor rejects construction; later shutdown can still reject submissions. The SDK does not silently switch to default executors.

Independent wheel stop/start is unsupported while communication components remain alive: restarting replaces the task
executor while existing components retain the old instance. Close those components and rebuild them to borrow the current
executor. Requests do not automatically restart the wheel. Constructing an enabled server builder may start the wheel;
the host still starts listeners, capability renewal, and persistent scanners separately.
See the [communication component guide](execution/README.en.md) for execution scope and configuration overloads.

## State and monitoring

Start intents and result Outbox share waiting, claim, and isolation states, but use different success states.

| Delivery state | Meaning | Operational focus |
| --- | --- | --- |
| PENDING | Waiting for eligibility or retry | Backlog, oldest age, next_attempt_at_ms |
| IN_FLIGHT | Worker holds a bounded lease | Reclaim only after expiry with a new token |
| COMMITTED / DUPLICATE | Start intent received the matching commit or duplicate receipt | Not workflow completion; retain start evidence |
| DELIVERED | Result Outbox received matching Committed/Duplicate | This result delivery completed; retain business and commit evidence |
| NEEDS_ATTENTION | Deterministic contract rejection or unavailable evidence | No automatic retry; inspect reason and original facts |

Monitor delivery backlog and age, expired claims, rejected stale settlements, reason-code distributions, capability expiry,
Ready, recovery mode, and shutdown outcomes separately. The SDK exports no automatic metrics endpoint. Hosts may derive
low-cardinality metrics from durable rows and dispatcher results. Business identities belong in controlled correlation logs,
not metric labels; do not log payloads, private keys, HMAC keys, or raw authentication headers.

Also record each communication component's execution policy and observe active requests, timeouts, rejected tasks,
database connection waits, and time spent in each shutdown phase. `TimingWheel.isStarted()` reports only the default
wheel's startup state; it does not establish Ready, valid capability, or remote connectivity. A live shared executor
also does not replace application concurrency limits.

Read the authoritative workflow state from Rust. COMPLETED, COMPENSATED, MANUAL_INTERVENTION, and MANUALLY_CLOSED have
different meanings; manual closure proves neither successful execution nor compensation.

## Failures and retry

| Receipt or reason | Action |
| --- | --- |
| Matching Committed/Duplicate | Settle only with the current token; start intent records its receipt state, result Outbox records DELIVERED |
| HTTP 408/425/429/5xx, temporary gRPC failure, timeout, disconnect | Retry original identity with backoff; remote commit may have happened |
| Authentication, authorization, identity conflict, definite protocol rejection | Check credentials, grants, and frozen request; do not invent a replacement identity |
| local_request_contract_invalid | Isolate damaged start intent and retain original bytes |
| local_result_contract_invalid | Isolate damaged result record/envelope and retain original bytes |
| local_result_evidence_unavailable | Isolate this unsupported business promise without sending |
| returned_snapshot_mismatch | Isolate a start receipt inconsistent with the frozen request |

Database unavailability is not message corruption; roll back a failed claim. Exhausted retry counts do not prove an unknown
external effect absent. HTTP retries retain event/command and body while using a fresh signing nonce.

## Protected admission and result recovery

Decide command admission separately from delivery of committed results. Invalid business provenance closes command, Ready,
and capability. Valid minimum result evidence may permit Outbox recovery, with a separate proof for every success,
compensation, rejection, or barrier. Recovery mode grants no exemption to unsupported success. An empty queue does not
automatically reopen business traffic.

When command routes are missing, Rust result authority still requires compatible Catalog and published definitions,
complete owner trust, live evidence, and valid lifecycle. Each request freezes expiry, revocation identity, security
publication generation, and contract digest. Lost authority after a SQL wait rolls back the result transaction and returns
a retryable outcome. New confirmation does not revive the old request. Any successful configuration publication may require
confirmation again, even when material bytes return to a previous value; this conservatively affects availability.

This cannot revoke a COMMIT already sent or prevent privileged external SQL writes after a readonly proof snapshot ends.
Maintenance must respect write isolation and database permissions. External effects need stable idempotency, trusted
resolution, or manual decisions.

## Manual handling and retention

Pause affected business writes and preserve instance, business facts, Inbox, gate, Outbox, and audit. Reconcile tenant,
definition digest, step, phase, effect, command/event identities, and initial input bindings. Do not infer original success
from a current row alone, delete orphan facts, or rewrite a result merely to obtain Ready. Ambiguous evidence remains isolated
for a business decision; restoring service requires full admission checks.

Committed Inbox and delivered result Outbox may still support duplicate commands, startup provenance, and late results. Do not delete them merely
because delivery completed. Retention covers audit, retry, compensation, and recovery windows and must align with Rust
definition retirement conditions.

## Bounded shutdown

1. Permanently revoke new command/Ready admission and renewal; stop new connections. Late receipts cannot reopen it.
2. Cancel queued work that has not started and await active local transactions, retaining their datasources.
3. Drain committed results using original identities, leases, and fencing, then close and await HTTP/gRPC components before closing database pools.
4. After all components depending on the default wheel finish, stop the wheel and its shared task executor.
5. Share one shutdown budget across all phases. On exhaustion, record unfinished stages and unknown effects and preserve
   durable rows for restart.

Socket closure or thread interruption does not prove rollback. Never mark uncertain delivery successful to exit promptly;
external effects retain their own idempotency and resolution requirements.
