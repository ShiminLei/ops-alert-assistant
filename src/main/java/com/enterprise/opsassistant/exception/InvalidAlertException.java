package com.enterprise.opsassistant.exception;

/**
 * 表示用户提交的告警文本为空或不满足最基本的输入要求。
 *
 * <p>继承 IllegalArgumentException 可以保持 Java 调用方的常规语义；独立类型则让后续 REST
 * 异常处理器能够准确返回 400，而不会把所有 IllegalArgumentException 都误认为用户错误。</p>
 */
public class InvalidAlertException extends IllegalArgumentException {

    /** @param message 可以安全返回给调用方的输入错误说明 */
    public InvalidAlertException(String message) {
        super(message);
    }
}
