/**
 * Java 与 Rust Saga 的客户端、参与方入口和可靠投递合同。
 *
 * <p>Rust 持有全局编排权威；Java 宿主通过同源业务事务、Inbox、gate 与 Outbox 保存本地事实。
 * 投递仅凭匹配的提交或重复收据前移，超时保留原身份；失权 worker 不得覆盖后续租约。</p>
 * <p>公开 record 位于 {@code io.github.nasaruntime.saga.rc}，持久化适配位于
 * {@code io.github.nasaruntime.saga.mybatis}。宿主负责 listener、数据库、扫描、逐事件业务证据与停机。</p>
 * <p>HTTP/gRPC 通信工厂默认按需启动 nasa-core 默认时间轮并借用其虚拟线程执行器。
 * {@link io.github.nasaruntime.saga.rc.SagaExecutionConfig} 可关闭该接入；关闭通信组件不会停止共享时间轮，
 * 宿主应在所有依赖组件关闭后停止时间轮，重启时间轮后必须重建通信组件。
 * 默认时间轮包含非守护调度线程，main 返回不能代替显式停机；时间轮启动状态也不能代表业务 Ready。</p>
 */
package io.github.nasaruntime.saga;
