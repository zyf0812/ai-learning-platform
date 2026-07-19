package com.exam.service;

import com.exam.mapper.UserMapper;
import com.exam.model.User;
import com.exam.util.JwtUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class AuthService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder encoder;
    private final TaskQueueService taskQueue;

    public AuthService(UserMapper userMapper, JwtUtil jwtUtil, PasswordEncoder encoder, TaskQueueService taskQueue) {
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
        this.encoder = encoder;
        this.taskQueue = taskQueue;
    }

    public Map<String, Object> register(String username, String password, String role) {
        User existing = userMapper.findByUsername(username);
        if (existing != null) throw new RuntimeException("用户名已存在");

        // 密码强度校验
        String pwError = checkPasswordStrength(password);
        if (pwError != null) throw new RuntimeException(pwError);

        User user = new User();
        user.setId(UUID.randomUUID().toString().substring(0, 8));
        user.setUsername(username);
        user.setPassword(encoder.encode(password));
        // 管理员需要审批，普通用户直接激活
        user.setRole(role != null && role.equals("admin") ? "admin" : "user");
        user.setStatus(role != null && role.equals("admin") ? "pending" : "active");
        userMapper.insert(user);

        if ("pending".equals(user.getStatus())) {
            return Map.of("message", "管理员申请已提交，请等待 root 审批");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        return Map.of("token", token, "user", Map.of("id", user.getId(), "username", user.getUsername(), "role", user.getRole()));
    }

    public Map<String, Object> login(String username, String password) {
        // 限流检查：5次失败后锁定15分钟
        String lockKey = "auth:lock:" + username;
        String attemptKey = "auth:attempt:" + username;
        String lockStatus = taskQueue.getStatus("", lockKey);
        if (lockStatus != null) {
            throw new RuntimeException("登录尝试过多，请15分钟后再试");
        }

        User user = userMapper.findByUsername(username);
        if (user == null || !encoder.matches(password, user.getPassword())) {
            // 记录失败次数
            String attempts = taskQueue.getStatus("", attemptKey);
            int count = attempts != null ? Integer.parseInt(attempts) : 0;
            count++;
            if (count >= 5) {
                taskQueue.set(lockKey, "locked", 15 * 60); // 锁15分钟
                taskQueue.del(attemptKey);
                throw new RuntimeException("密码错误次数过多，账号已锁定15分钟");
            }
            taskQueue.set(attemptKey, String.valueOf(count), 30 * 60); // 30分钟窗口
            int remaining = 5 - count;
            throw new RuntimeException("用户名或密码错误，还剩" + remaining + "次尝试机会");
        }

        // 登录成功，清除尝试记录
        taskQueue.del(attemptKey);
        taskQueue.del(lockKey);

        if ("pending".equals(user.getStatus())) {
            throw new RuntimeException("你的管理员申请正在审批中，请等待 root 审核");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        return Map.of("token", token, "user", Map.of("id", user.getId(), "username", user.getUsername(), "role", user.getRole()));
    }

    public Map<String, Object> me(String userId) {
        User user = userMapper.findById(userId);
        if (user == null) throw new RuntimeException("用户不存在");
        return Map.of("id", user.getId(), "username", user.getUsername(), "role", user.getRole(), "createdAt", user.getCreatedAt());
    }

    private String checkPasswordStrength(String password) {
        if (password.length() < 8) return "密码长度至少8位";

        boolean hasUpper = false, hasLower = false, hasDigit = false, hasSpecial = false;
        for (char c : password.toCharArray()) {
            if (c >= 'A' && c <= 'Z') hasUpper = true;
            else if (c >= 'a' && c <= 'z') hasLower = true;
            else if (c >= '0' && c <= '9') hasDigit = true;
            else hasSpecial = true;
        }

        int kinds = 0;
        if (hasUpper) kinds++;
        if (hasLower) kinds++;
        if (hasDigit) kinds++;
        if (hasSpecial) kinds++;

        if (kinds < 2) return "密码需包含字母和数字";
        if (kinds < 3) return "建议密码包含大写字母、小写字母、数字和特殊字符中的至少3种";

        // 常见弱密码
        String[] weak = {"12345678", "123456789", "password", "qwerty123", "abc123456", "88888888", "11111111"};
        for (String w : weak) {
            if (password.equalsIgnoreCase(w)) return "密码过于简单，请换一个";
        }

        return null; // 通过
    }
}
