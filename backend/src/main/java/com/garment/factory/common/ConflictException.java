package com.garment.factory.common;

/**
 * 状态冲突异常：并发竞争失败、重复交接/接收、状态机不允许的流转等返回 409，
 * 与普通业务校验失败（400）区分，前端可据此提示「竞争失败请刷新」。
 */
public class ConflictException extends BizException {

    public ConflictException(String message) {
        super(message);
    }
}
