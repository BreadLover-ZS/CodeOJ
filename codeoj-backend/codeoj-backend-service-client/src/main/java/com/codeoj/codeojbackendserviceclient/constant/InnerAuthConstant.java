package com.codeoj.codeojbackendserviceclient.constant;

/**
 * 内部接口鉴权常量
 *
 * <p>内部接口（/inner/**）仅允许服务间调用，不能暴露给前端或外部直连。
 * 服务间通过 Feign 自动携带 {@link #X_INNER_SECRET} 请求头，
 * 各服务与网关通过校验该请求头防止越权访问。
 */
public interface InnerAuthConstant {

    /**
     * 内部调用鉴权请求头名称
     */
    String X_INNER_SECRET = "X-Inner-Secret";

    /**
     * 内部鉴权密钥配置项（生产环境务必通过环境变量 INNER_AUTH_SECRET 覆盖）
     */
    String INNER_AUTH_SECRET_KEY = "codeoj.inner-auth-secret";

    /**
     * 默认密钥（仅用于本地开发，生产环境必须覆盖）
     */
    String DEFAULT_INNER_AUTH_SECRET = "codeoj-default-inner-secret";
}