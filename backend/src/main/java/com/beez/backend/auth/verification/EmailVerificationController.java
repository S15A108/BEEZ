package com.beez.backend.auth.verification;

import com.beez.backend.common.api.Ids;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** ACC-A11 가입 인증 코드 보내기, ACC-A12 가입 인증 코드 확인. 둘 다 PUBLIC (SecurityConfig). */
@Tag(name = "auth", description = "회원가입·로그인·로그아웃")
@RestController
@RequestMapping("/api/auth/email-verifications")
public class EmailVerificationController {

    private final EmailVerificationService verifications;

    public EmailVerificationController(EmailVerificationService verifications) {
        this.verifications = verifications;
    }

    public record SendRequest(@NotBlank @Email @Size(max = 254) String email) {

        /** 가입 요청(RegisterRequest)과 같이 @Email 검증 전에 앞뒤 공백을 지운다 */
        public SendRequest {
            email = email == null ? null : email.trim();
        }
    }

    public record SendResponse(String verificationId, Instant expiresAt, Instant resendAvailableAt) {
    }

    public record ConfirmRequest(@NotBlank @Pattern(regexp = "\\d{6}", message = "6자리 숫자여야 합니다") String code) {
    }

    public record ConfirmResponse(String verifiedToken, String email, Instant expiresAt) {
    }

    @Operation(summary = "가입 인증 코드 보내기 (ACC-A11). 다시 받으면 이전 코드는 바로 무효")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SendResponse send(@Valid @RequestBody SendRequest body) {
        EmailVerificationService.Sent sent = verifications.send(body.email());
        return new SendResponse(Ids.str(sent.verificationId()), sent.expiresAt(), sent.resendAvailableAt());
    }

    @Operation(summary = "가입 인증 코드 확인 (ACC-A12). 성공하면 회원가입에 넣을 1회용 verified_token")
    @PostMapping("/{verificationId}/confirm")
    public ConfirmResponse confirm(@PathVariable String verificationId, @Valid @RequestBody ConfirmRequest body) {
        EmailVerificationService.Confirmed confirmed =
                verifications.confirm(Ids.parse(verificationId, "verification_id"), body.code());
        return new ConfirmResponse(confirmed.verifiedToken(), confirmed.email(), confirmed.expiresAt());
    }
}
