package com.secondclassroom.service;

public interface SmsSender {
    void sendCode(String phone, String code, String scene, int expiresInSeconds);
}
