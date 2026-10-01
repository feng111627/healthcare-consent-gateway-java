package com.github.feng111627.healthcareconsentgatewayjava.service;

import com.github.feng111627.healthcareconsentgatewayjava.domain.AccessRequest;
import com.github.feng111627.healthcareconsentgatewayjava.model.GatewayResponse;

/** 网关编排：判定 -> 留痕 -> 取数 -> 脱敏 -> 返回。 */
public interface GatewayService {
    GatewayResponse access(AccessRequest request);
}
