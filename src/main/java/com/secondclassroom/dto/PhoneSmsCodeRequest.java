package com.secondclassroom.dto;

public class PhoneSmsCodeRequest {
    private String target;
    private String newPhone;

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getNewPhone() { return newPhone; }
    public void setNewPhone(String newPhone) { this.newPhone = newPhone; }
}
