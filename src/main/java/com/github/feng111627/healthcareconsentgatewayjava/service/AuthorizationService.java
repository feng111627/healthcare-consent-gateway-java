package com.github.feng111627.healthcareconsentgatewayjava.service;

import com.github.feng111627.healthcareconsentgatewayjava.domain.AccessDecision;
import com.github.feng111627.healthcareconsentgatewayjava.domain.AccessRequest;
import com.github.feng111627.healthcareconsentgatewayjava.domain.DecisionBasis;
import com.github.feng111627.healthcareconsentgatewayjava.domain.DecisionReason;

import java.time.Instant;

/**
 * 授权判定：结合请求方用途判定授权是否成立。
 * 拒绝原因可区分：无授权 / 已撤回 / 已过期 / 越权。
 */
public interface AuthorizationService {
    AccessDecision evaluate(AccessRequest request, Instant at);

    /**
     * 用历史时刻的规则版本 + 已留痕的依据快照复现判定，
     * 用于审计复盘（不依赖当前授权状态）。
     */
    AccessDecision replay(DecisionBasis basis, String policyVersion);
}
