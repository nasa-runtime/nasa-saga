# 贡献指南

[中文](CONTRIBUTING.md) | [English](CONTRIBUTING.en.md)

nasa-saga 提供 Java client、participant 合同和持久化适配；全局编排由 Rust Orchestrator 负责。
涉及新协议、状态语义或公开 API 的变更，请先通过仓库 Issue 说明业务场景、兼容性影响和预期行为。
范围明确的文档和代码改进可以直接提交 Pull Request。安全问题请按 [安全策略](SECURITY.md) 私下报告。

## 开发环境与构建

使用 JDK 21 或更高版本、Maven 3.6.3 或更高版本：

```bash
mvn -B -ntp clean verify
```

Maven 的 JDK 要求为 `[21,)`，不设版本上限；`release=21` 固定编译产物的兼容基线。
使用更高版本 JDK 构建时仍保持此基线，避免产物无意依赖较新的 API 或字节码。

构建生成 protobuf/gRPC 类型、主 JAR、sources JAR 和 Javadoc JAR。生成类不手工维护；协议源位于
`src/main/proto`，其 wire 合同与 Rust `nasaga-runtime-core/proto` 一致。

## 代码与文档

- record 使用 `io.github.nasaruntime.saga.rc`；服务接口和 transport 使用根包，MyBatis 适配使用 `mybatis` 包。
- 公共 API、数据表或 protobuf 变更须说明源码、二进制、持久数据及网络合同的兼容性影响。
- 保持本地业务事务、Inbox、gate 与 Outbox 的原子性；网络不确定结论不能被当作已提交或确定回滚。
- 注释使用中文，方法说明业务作用、参数含义、返回条件与副作用；关键准入和失权步骤说明原因。
- 文档与注释只描述当前合同，不记录内部过程、工具归因或开发批次；示例不得嵌入真实凭据。
- 公开内容限于产品源码、协议、配置和使用说明，不包含本机辅助工程、运行记录、IDE 文件或生成目录。

README、接入指南、Javadoc、SQL 和 manifest 元数据须与公开行为同步。Pull Request 说明变更目的、行为、
兼容性和验证方式；对不能确认的外部效果，应保留原身份与证据，明确人工处理边界。

## 许可证

贡献按照 [Apache-2.0 OR MIT](LICENSE) 双许可证提供，与项目采用相同条款。
