# Protocol and compatibility

[中文](protocol.md) | [English](protocol.en.md)

## Authoritative sources

Java generation starts from [saga_transport.proto](https://github.com/nasa-runtime/nasa-saga/blob/master/src/main/proto/saga_transport.proto) and
[saga_orchestrator.proto](https://github.com/nasa-runtime/nasa-saga/blob/master/src/main/proto/saga_orchestrator.proto). Wire packages, field numbers, RPCs, and enum values match
Rust `nasaga-runtime-core/proto`; Java options only determine local class layout. Compatibility depends on those contracts,
not equal-looking version numbers. Handwritten public records are in `io.github.nasaruntime.saga.rc`; generated packages
are declared by each proto.

| Capability | Java API | Confirmation boundary |
| --- | --- | --- |
| start/get/query/audit | SagaControlPlane | Rust authority, no Java global state machine |
| Raw gRPC management/registry | RustSagaClient | Separate management permissions |
| Inbound commands | HTTP ingress / gRPC service | ACK after local business and control commit |
| Outbound results | HTTP publisher / gRPC transport client | Advance only on matching Committed/Duplicate |
| Capability registration | HTTP/gRPC registrar | Full contract and live lease before command admission |

Java supplies no global Orchestrator/Admin/Registry server or Kafka/Redis Streams connector.

## Stable identities and raw payloads

Saga, trigger, and business key identify a start intent. Effect identifies the business operation. Command identifies an
action attempt and remains unchanged during network retries. Result event derives from command identity. Different attempts
may address the same effect; committed decisions, not repeated business execution, determine replay.

Start input and payload are mutually exclusive. SagaPayload preserves content_type, schema_id, and bytes; HTTP uses a byte
array of numbers and gRPC uses bytes. Reliable start stores the final outbound body and a local integrity hash; that SHA-256
is not Rust's semantic request_digest.

Before Java binding, protocol DTOs reject duplicate fields, implicit conversions, noninteger protocol numbers, and root null.
JSON requires BOM-free UTF-8 and valid Unicode scalars. Business duplicate keys retain last-value-wins semantics, but overwritten
values still satisfy encoding, number, and depth limits. Each independent document permits 127 nested containers. Non-JSON
media preserve their bytes under the declared schema. Identical floating canonical text across runtimes is not guaranteed.

## Receipts and uncertainty

Start receipts require a valid semantic digest and matching tenant, Saga, workflow, business key, definition version, and
any requested definition digest. Missing fields, scope mismatch, or corrupted responses prove neither success nor absence
of a remote commit.

The four command/result receipts distinguish committed, duplicate, retryable, and deterministic rejection. Authenticated
identity must match the step owner or trusted Orchestrator relationship. Authentication does not replace business evidence
or transaction authority. Read APIs also verify identity, filters, and pagination; absent collections are not empty results.

HTTP capability receipts include positive catalog generation. Current gRPC protobuf omits it, so Java's 0 means not supplied,
not Catalog authority. Both validate the full digest, route generation, and expiry. The digest includes `replica_identity`,
binding the registered replica and route. HTTP receipts have no separate registration ID field; gRPC additionally checks
the response's `registration_id` explicitly. Unknown enums and unsupported states cannot be treated as success.

## HTTP paths and pagination

HTTP `get` and `audit` place `tenant_id` and `saga_id` in path segments. These identities may contain only URI unreserved
ASCII characters: `A-Z`, `a-z`, `0-9`, `.`, `_`, `-`, and `~`. The SDK does not escape or normalize them. JSON fields in
start/query and gRPC do not have this HTTP path restriction. Choose the shared character range when creating identities
that will later be accessed through HTTP `get/audit`.

`query` uses `POST /instances/query`. `audit` uses `GET /instances/{tenant}/{saga}/audit`; pagination, when present, travels
in a JSON body containing `page_size` and `page_token`. Proxies must preserve this GET body without discarding it or moving
it into query parameters. HMAC covers the actual path and original body. These routes are relative to the Saga base URI.

## Coordinator requirements for result recovery

Matching protobuf/JSON fields alone does not establish that a deployed coordinator accepts results independently after
command routing closes. This recovery path requires Rust Orchestrator to authorize results separately and continue checking
Catalog, frozen definitions, owner trust, evidence expiry, security publication generation, and lifecycle after transaction
waits. If the coordinator closes result reception together with command routing, Java retries alone cannot complete recovery.
Java must still enforce event-specific business evidence, stable identities, and leases. The SDK does not change coordinator
admission policy.

## Types and stored data

Record packages and signatures are Java source and binary API. MyBatis parameters and results use the same rc records.
Recompile applications against the matching SDK; do not combine old class files with the current records. Package layout
does not change JSON/protobuf fields, identity derivation, or table schema.

Changes to protocol fields, enums, digests, SQL columns, and decisions must account for stored requests, uncertain commits,
historical Outbox, original inputs, and late results. Initialization SQL does not migrate arbitrary history. New evidence can
only come from trustworthy original facts. See [Operations](operations.en.md).
