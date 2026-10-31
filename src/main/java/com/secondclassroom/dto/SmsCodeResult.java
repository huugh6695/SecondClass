package com.secondclassroom.dto;

public class SmsCodeResult {
    private int cooldownSeconds;
    /** 仅本地开发环境返回，生产必须关闭。 */
    private String debugCode;

    public SmsCodeResult() {}

    public SmsCodeResult(int cooldownSeconds, String debugCode) {
        this.cooldownSeconds = cooldownSeconds;
        this.debugCode = debugCode;
    }

    public int getCooldownSeconds() { return cooldownSeconds; }
    public void setCooldownSeconds(int cooldownSeconds) { this.cooldownSeconds = cooldownSeconds; }

    public String getDebugCode() { return debugCode; }
    public void setDebugCode(String debugCode) { this.debugCode = debugCode; }
}
