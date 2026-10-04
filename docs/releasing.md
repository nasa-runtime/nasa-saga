# Maven Central 发布

[中文](releasing.md) | [English](releasing.en.md)

nasa-saga 使用 `central-release` Maven profile 向 Central Portal 提供签名制品。普通构建生成主 JAR、
sources JAR 与 Javadoc JAR；启用该 profile 后，`verify` 为三个 JAR 和 POM 签名，`deploy` 才会上传。
`autoPublish=false` 使上传后的部署停留在人工发布流程中；上传成功不等于公开可下载。

## 环境与权限

- 使用 JDK 21 或更高版本、Maven 3.6.3 或更高版本，以及 GnuPG。产物保持 `release=21`。
- Central Portal 账户须拥有 `io.github.nasa-runtime` 命名空间的发布权限。
- 使用 Central Portal 的 user token；Maven server ID 必须为 `central`。
- 本机须能访问带签名能力且未过期的私钥；相应公钥须可从 Central 支持的公钥服务器获取。
- 密钥、token 和签名口令由发布环境管理，不进入源码、命令行参数、日志或制品。

将以下 server 合入用户级 `~/.m2/settings.xml` 的 `servers`，通过环境变量提供 token：

```xml
<server>
    <id>central</id>
    <username>${env.MAVEN_CENTRAL_USERNAME}</username>
    <password>${env.MAVEN_CENTRAL_PASSWORD}</password>
</server>
```

已有可用的同名 server 可以继续使用，不要重复定义。该凭据只提供给 Central Portal。
签名使用 `gpg-agent` 已解锁的密钥，或环境变量 `MAVEN_GPG_PASSPHRASE`；不要使用
`-Dgpg.passphrase`。`bestPractices=true` 拒绝插件识别出的不安全口令配置。存在多个签名密钥时，
可以使用非敏感的 `-Dgpg.keyname=<fingerprint>` 明确选择签名者。

## 构建与签名

普通构建不需要发布凭据或私钥：

```bash
mvn -B -ntp clean verify
```

main、release 和相应 Pull Request 的 CI 在 JDK 21、25、27 上执行普通构建，并启用发布 profile
运行 `validate`，拒绝 SNAPSHOT 项目坐标和依赖。CI 的 `validate` 阶段不签名、不上传。

在具备签名条件的发布环境生成签名制品：

```bash
mvn -B -ntp -Pcentral-release clean verify
```

`central-release` 使用 `maven-gpg-plugin` 在 `verify` 阶段为主包、sources、Javadoc 与 POM 生成
分离签名。签名通常位于 `target` 或 `target/gpg`；POM 的签名对象是构建输出中的发布 POM。
使用 `gpg --verify <signature.asc> <artifact>` 逐项核对签名与对应文件。

固定的 `project.build.outputTimestamp` 统一归档时间戳，降低无关文件时间导致的差异；跨 JDK 或
构建工具的输出不因此保证逐字节相同。公开交付必须使用同一组已核对的制品。

## 制品合同

- POM、README 依赖坐标、归档文件名与嵌入 POM 使用相同版本；目标坐标不得覆盖 Central 既有制品。
- 直接依赖与传递依赖须可从公开仓库解析，不依赖发布机器上额外安装的同坐标实现。
- 主包包含运行类、protobuf、SQL、处理器服务注册、中英文说明和 Apache-2.0 OR MIT 许可证。
- sources 与运行类对应；Javadoc 包含公开 API。三个归档均携带完整中英文说明与许可证。
- 直接读取最终归档中的文档、元数据和文件清单，确认使用说明与源码一致，不包含本机工具、
  执行记录、凭据或工作目录。
- 项目名、description、开发者、许可证与 SCM 信息必须完整；JDK 门禁为 `[21,)`，字节码基线为 21。
- 主 JAR、sources JAR、Javadoc JAR、POM 均须有有效签名。Central 插件在上传 bundle 中生成
  MD5、SHA-1、SHA-256 和 SHA-512 校验文件。

## 上传与公开状态

先提交并推送 main、release，等待目标提交的所有 CI job 成功，再复核远端 SHA 与本地制品所对应的提交。
维护者明确授权具体坐标、版本与提交后，执行上传：

```bash
mvn -B -ntp -Pcentral-release deploy
```

该命令会重新运行 Maven 生命周期，因此上传前须确认工作区和构建环境对应同一提交。
`publishingServerId=central` 选择上述凭据，`checksums=all` 生成校验文件，`waitUntil=validated`
等待 Portal 的校验结果。`autoPublish=false` 保留人工发布步骤；不要仅依据 Maven 成功退出判断发布完成。

在 Central Portal 检查该部署的坐标、文件清单与校验状态，确认内容与授权一致后再完成发布。
状态达到 `PUBLISHED` 后，从 Central 回读 POM、三个 JAR、签名及校验文件，核对摘要、版本、文档与许可证。
如创建 GitHub tag 或 Release，其提交与附件须对应相同的交付内容。

Central 版本不可原地替换。内容遗漏或错误需要新的补丁版本；本地改动不会改变已经公开的归档。

参考 [Central Maven 插件](https://central.sonatype.org/publish/publish-portal-maven/)、
[制品要求](https://central.sonatype.org/publish/requirements/) 和
[GPG 签名配置](https://maven.apache.org/plugins/maven-gpg-plugin/sign-mojo.html)。
