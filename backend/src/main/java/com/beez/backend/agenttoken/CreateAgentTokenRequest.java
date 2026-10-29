package com.beez.backend.agenttoken;

/** AGT-A01 본문. label 은 선택이라 본문 없이 보내도 된다. 길이는 앞뒤 공백을 뺀 뒤 서비스에서 본다. */
public record CreateAgentTokenRequest(String label) {
}
