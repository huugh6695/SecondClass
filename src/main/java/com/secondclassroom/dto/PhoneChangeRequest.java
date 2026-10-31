package com.secondclassroom.dto;

public class PhoneChangeRequest {
    private String newPhone;
    private String oldSmsCode;
    private String newSmsCode;

    public String getNewPhone() { return newPhone; }
    public void setNewPhone(String newPhone) { this.newPhone = newPhone; }

    public String getOldSmsCode() { return oldSmsCode; }
    public void setOldSmsCode(String oldSmsCode) { this.oldSmsCode = oldSmsCode; }

    public String getNewSmsCode() { return newSmsCode; }
    public void setNewSmsCode(String newSmsCode) { this.newSmsCode = newSmsCode; }
}
