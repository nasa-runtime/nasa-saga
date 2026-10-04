package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaCapabilityDescriptor;
import io.github.nasaruntime.saga.rc.SagaCapabilityReceipt;

/**
 * 宿主通过显式认证的 registry 通道取得权威租约，不在本地生成路由代际。
 */
@FunctionalInterface
public interface SagaCapabilityRegistrar {
    /**
     * 业务作用：向 Rust 登记完整能力合同并复验服务端租约、代际和摘要。
     *
     * @param descriptor  本副本不可变的业务与路由合同
     * @param traceparent 可选链路上下文
     * @return 绑定当前 descriptor 的未过期收据；认证、网络或合同失败时拒绝开放路由。
     */
    SagaCapabilityReceipt register(SagaCapabilityDescriptor descriptor, String traceparent);
}
