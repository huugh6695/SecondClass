package com.secondclassroom.dto;

public class LoginSmsVerifyRequest {
    private String challengeId;
    private String smsCode;

    public String getChallengeId() { return challengeId; }
    public void setChallengeId(String challengeId) { this.challengeId = challengeId; }

    public String getSmsCode() { return smsCode; }
    public void setSmsCode(String smsCode) { this.smsCode = smsCode; }
}
