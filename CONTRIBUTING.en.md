# Contributing

[中文](CONTRIBUTING.md) | [English](CONTRIBUTING.en.md)

nasa-saga provides Java clients, participant contracts, and persistence adapters. Global orchestration belongs to Rust.
For new protocols, state semantics, or public APIs, open an issue describing the business case, expected behavior, and
compatibility impact. Scoped documentation and code improvements may be submitted directly as pull requests.
Report vulnerabilities privately as described in [Security](SECURITY.en.md).

## Environment and build

Use JDK 21+ and Maven 3.6.3+:

```bash
mvn -B -ntp clean verify
```

The build generates protobuf/gRPC types, the main JAR, sources JAR, and Javadoc JAR. Do not edit generated classes.
Protocol sources are in `src/main/proto` and retain the wire contract from Rust `nasaga-runtime-core/proto`.

## Code and documentation

- Public records belong in `io.github.nasaruntime.saga.rc`; service and transport APIs use the root package; MyBatis
  adapters use `.mybatis`.
- Explain source, binary, persistent-data, and wire compatibility for public API, schema, or protobuf changes.
- Preserve atomic business/Inbox/gate/Outbox writes. An uncertain network outcome is neither committed success nor proof of rollback.
- Write code comments in Chinese. Methods describe business purpose, meaningful parameters, return conditions, and side effects;
  important admission and authority transitions explain their reason.
- Public documentation describes the current contract, without internal process records, tool attribution, or development batches.
  Examples must not contain real credentials.
- Public contributions contain product source, protocols, configuration, and user documentation; exclude local auxiliary
  projects, run records, IDE metadata, and generated files.

Keep README, guides, Javadoc, SQL, and manifest metadata consistent with public behavior. Pull requests describe the purpose,
behavior, compatibility, and validation. Preserve original identities and evidence for uncertain external effects and state
the boundary for manual decisions.

## License

Contributions are provided under the project's [Apache-2.0 OR MIT](LICENSE) dual license.
