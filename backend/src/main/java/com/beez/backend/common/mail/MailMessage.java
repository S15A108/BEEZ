package com.beez.backend.common.mail;

/** 보낼 메일 한 통. 본문은 일반 텍스트다. */
public record MailMessage(String to, String subject, String text) {
}
