# 安全策略

[中文](SECURITY.md) | [English](SECURITY.en.md)

## 支持范围

安全维护面向当前最新发布版本。SDK 的身份认证、租约、持久去重和输入边界须与部署的 Rust Saga 运行时共同配置；
只升级一侧不能替代跨语言合同核对。公开制品不可原地覆盖，安全更新以新版本提供。

## 私下报告

请使用 [GitHub Security Advisory](https://github.com/nasa-runtime/nasa-saga/security/advisories/new) 私下报告漏洞，
包括受影响版本、transport、数据库、触发前提、权限范围和最小复现信息。提交前移除私钥、HMAC key、访问令牌、
真实业务正文及可识别用户的数据。请勿在安全更新公开前创建包含可利用细节的公开 Issue。

维护者会核对影响、准备安全更新并协调披露。一般功能建议和非安全缺陷可通过仓库 Issue 讨论。

## 部署边界

- HTTP 使用与身份绑定的 HMAC、时间窗和持久 replay 防护，部署层仍须提供传输机密性与网络隔离。
- gRPC 使用双向 TLS，将叶证书 principal 映射到受信逻辑 producer；不能信任正文自报身份。
- 业务 client、participant、workflow owner 与管理主体分别授权，管理接口不使用普通业务凭据。
- 数据库权限应阻止绕过 participant 协议改写事实、Inbox、gate 和 Outbox；只读校验无法封锁其它连接后续写入。
- 重投保留业务与事件身份；隔离事件、未知效果和人工介入不能通过删除证据或填造成功记录消除。

资源上限、恢复和停机要求见 [运维指南](docs/operations.md)。
