package com.beez.backend.common.mail;

/** 메일을 보내지 못했다. 메시지·원인에 받는 사람 주소나 본문을 넣지 않는다 (로그로 새지 않게). */
public class MailDeliveryException extends RuntimeException {

    public MailDeliveryException(String message) {
        super(message);
    }

    public MailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
