package com.exam.controller;

import com.exam.dto.LoginRequest;
import com.exam.dto.RegisterRequest;
import com.exam.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final CaptchaController captchaController;

    public AuthController(AuthService authService, CaptchaController captchaController) {
        this.authService = authService;
        this.captchaController = captchaController;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest body) {
        try {
            // 验证验证码
            if (!captchaController.verify(body.getCaptchaToken(), body.getCaptchaCode())) {
                return ResponseEntity.badRequest().body(Map.of("error", "验证码错误或已过期"));
            }
            String role = body.getRole() != null ? body.getRole() : "user";
            return ResponseEntity.ok(authService.register(body.getUsername(), body.getPassword(), role));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest body) {
        try {
            return ResponseEntity.ok(authService.login(body.getUsername(), body.getPassword()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication auth) {
        return ResponseEntity.ok(Map.of("user", authService.me(auth.getName())));
    }
}
