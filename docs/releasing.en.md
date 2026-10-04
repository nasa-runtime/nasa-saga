# Publishing to Maven Central

[中文](releasing.md) | [English](releasing.en.md)

nasa-saga uses the `central-release` Maven profile to deliver signed artifacts to Central Portal. A normal build creates
the main, sources, and Javadoc JARs. With this profile, `verify` signs the three JARs and the POM; `deploy` uploads them.
`autoPublish=false` retains a manual publication step after upload. A successful upload does not mean public availability.

## Environment and permissions

- Use JDK 21 or later, Maven 3.6.3 or later, and GnuPG. Artifacts retain `release=21`.
- The Central Portal account must have publishing permission for `io.github.nasa-runtime`.
- Use a Central Portal user token with the Maven server ID `central`.
- The release environment must have access to an unexpired private key with signing capability. Central must be able to
  retrieve its public key from a supported keyserver.
- The release environment manages keys, tokens, and passphrases; keep them out of source, command arguments, logs, and artifacts.

Merge this server into `servers` in the user-level `~/.m2/settings.xml`, supplying the token through environment variables:

```xml
<server>
    <id>central</id>
    <username>${env.MAVEN_CENTRAL_USERNAME}</username>
    <password>${env.MAVEN_CENTRAL_PASSWORD}</password>
</server>
```

An existing working server with that ID can remain in use; do not define it twice. These credentials are for Central Portal.
Signing uses a key already unlocked through `gpg-agent`, or `MAVEN_GPG_PASSPHRASE`. Do not use `-Dgpg.passphrase`.
`bestPractices=true` rejects unsafe passphrase configuration recognized by the plugin. When several signing keys are
available, select one with the nonsensitive `-Dgpg.keyname=<fingerprint>` option.

## Build and sign

A normal build does not require publishing credentials or a private key:

```bash
mvn -B -ntp clean verify
```

CI builds main, release, and their pull requests on JDK 21, 25, and 27. It also runs `validate` with the release profile to
reject SNAPSHOT project coordinates and dependencies. This `validate` phase neither signs nor uploads artifacts.

Generate signed artifacts in an environment with signing access:

```bash
mvn -B -ntp -Pcentral-release clean verify
```

The profile runs `maven-gpg-plugin` at `verify` to create detached signatures for the main, sources, and Javadoc JARs
and the POM. Signatures normally reside in `target` or `target/gpg`; the signed POM is the publication POM in the build
output. Check every signature against its artifact with `gpg --verify <signature.asc> <artifact>`.

The fixed `project.build.outputTimestamp` normalizes archive timestamps to reduce differences caused by unrelated file
times. It does not guarantee byte-identical output across JDKs or build tools. Public delivery must use one verified set
of artifacts.

## Artifact contract

- POM, README dependency coordinates, archive names, and embedded POM use the same version. The coordinates must not
  overwrite existing Central artifacts.
- Direct and transitive dependencies must resolve from public repositories without additional locally installed
  implementations under the same coordinates.
- The main JAR contains runtime classes, protobuf, SQL, processor service registration, Chinese/English documentation,
  and the Apache-2.0 OR MIT licenses.
- Sources correspond to runtime classes, and Javadoc covers public APIs. All three archives contain the complete
  Chinese/English documentation and licenses.
- Read documentation, metadata, and file lists directly from the final archives. They must agree with source and exclude
  local tools, execution records, credentials, and working directories.
- Project name, description, developers, licenses, and SCM metadata must be complete. The JDK gate is `[21,)`, with
  Java 21 bytecode as the baseline.
- The main, sources, and Javadoc JARs and the POM each need a valid signature. The Central plugin generates MD5, SHA-1,
  SHA-256, and SHA-512 checksum files in the upload bundle.

## Upload and public availability

Commit and push main and release, wait for every CI job on the target commit to succeed, and verify that the remote SHA
matches the commit represented by the local artifacts. After a maintainer explicitly authorizes the coordinates, version,
and commit, upload with:

```bash
mvn -B -ntp -Pcentral-release deploy
```

This command runs the Maven lifecycle again, so confirm that the working tree and build environment represent the same
commit before uploading. `publishingServerId=central` selects the credentials above, `checksums=all` generates checksums,
and `waitUntil=validated` waits for Portal validation. `autoPublish=false` retains manual publication; Maven exiting
successfully does not establish that publication is complete.

Inspect the deployment's coordinates, file list, and validation state in Central Portal. Complete publication only when
they match the authorization. After the state reaches `PUBLISHED`, retrieve the POM, all three JARs, signatures, and
checksums from Central and compare digests, versions, documentation, and licenses. A GitHub tag or Release, if created,
must use the matching commit and artifacts.

Central versions cannot be replaced in place. Missing or incorrect content requires a new patch version; local changes
cannot alter an already-public archive.

See the [Central Maven plugin](https://central.sonatype.org/publish/publish-portal-maven/),
[artifact requirements](https://central.sonatype.org/publish/requirements/), and
[GPG signing configuration](https://maven.apache.org/plugins/maven-gpg-plugin/sign-mojo.html).
