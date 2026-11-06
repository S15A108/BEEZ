-- AI 제안은 서버의 resolution/next_action과 별개다. 기존 이력과 규칙 finding은 null이다.
alter table validation_finding add column ai_suggested_action text;
