package com.github.feng111627.healthcareconsentgatewayjava.domain;

/** 一次患者数据读取请求。 */
public record AccessRequest(
        String requesterId,
        String patientId,
        Purpose purpose
) {
}
