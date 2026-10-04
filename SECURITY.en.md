# Security policy

[中文](SECURITY.md) | [English](SECURITY.en.md)

## Supported scope

Security maintenance targets the latest published version. SDK authentication, leases, durable deduplication, and input
constraints must be configured together with the deployed Rust Saga runtime. Updating one side does not replace checking
the cross-language contract. Published artifacts are immutable; security updates use a new version.

## Private reports

Use [GitHub Security Advisory](https://github.com/nasa-runtime/nasa-saga/security/advisories/new) to report vulnerabilities
privately. Include affected versions, transport, database, preconditions, required permissions, and a minimal reproduction.
Remove private keys, HMAC keys, access tokens, real business payloads, and identifying user data. Do not open public issues
containing exploitable details before coordinated disclosure.

Maintainers assess the impact, prepare an update, and coordinate disclosure. Ordinary defects and feature requests belong in issues.

## Deployment boundaries

- HTTP binds HMAC credentials to identities and uses time windows and durable replay protection. Deployment still supplies
  transport confidentiality and network isolation.
- gRPC uses mutual TLS and maps leaf-certificate principals to trusted logical producers. Payload identity is not authentication.
- Separate permissions for business clients, participants, workflow owners, and administrators.
- Database permissions must prevent out-of-protocol changes to business facts, Inbox, gate, and Outbox. A readonly check
  cannot block later writes from another connection.
- Retain stable identities when retrying. Deleting evidence or fabricating success cannot resolve isolated events, unknown
  effects, or manual-intervention states.

See [Operations](docs/operations.en.md) for resource limits, recovery, and shutdown requirements.
