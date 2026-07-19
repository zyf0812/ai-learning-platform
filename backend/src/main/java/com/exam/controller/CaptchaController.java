package com.exam.controller;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/auth")
public class CaptchaController {

    private final StringRedisTemplate redis;
    private final Random rand = new Random();

    // 容易识别的字符集合（去掉 0/O, 1/I/L, 5/S, 8/B 等易混淆的）
    private static final List<Character> CHARS = "234679ABCDEFGHJKMNPQRTUVWXYZ".chars()
            .mapToObj(c -> (char) c).toList();

    public CaptchaController(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @GetMapping("/captcha")
    public Map<String, String> captcha() {
        String code = generateCode(4);
        String token = UUID.randomUUID().toString().substring(0, 12);

        // 存入 Redis，5 分钟过期
        redis.opsForValue().set("captcha:" + token, code, 5, TimeUnit.MINUTES);

        // 生成图片
        String imageBase64 = generateImage(code);

        return Map.of("token", token, "image", "data:image/png;base64," + imageBase64);
    }

    private String generateCode(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(CHARS.get(rand.nextInt(CHARS.size())));
        }
        return sb.toString();
    }

    private String generateImage(String code) {
        int w = 120, h = 40;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();

        // 背景
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);

        // 噪点
        g.setColor(new Color(220, 220, 220));
        for (int i = 0; i < 80; i++) {
            g.fillRect(rand.nextInt(w), rand.nextInt(h), 2, 2);
        }

        // 干扰线
        for (int i = 0; i < 3; i++) {
            g.setColor(new Color(rand.nextInt(200), rand.nextInt(200), rand.nextInt(200)));
            g.drawLine(rand.nextInt(w), rand.nextInt(h), rand.nextInt(w), rand.nextInt(h));
        }

        // 验证码文字
        g.setFont(new Font("Monospaced", Font.BOLD, 24));
        for (int i = 0; i < code.length(); i++) {
            g.setColor(new Color(rand.nextInt(80), rand.nextInt(80), rand.nextInt(80)));
            int x = 10 + i * 26;
            int y = 30 + rand.nextInt(6) - 3;
            g.drawString(String.valueOf(code.charAt(i)), x, y);
        }

        g.dispose();

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException("生成验证码失败", e);
        }
    }

    public boolean verify(String token, String code) {
        if (token == null || code == null) return false;
        String key = "captcha:" + token;
        String stored = redis.opsForValue().get(key);
        if (stored == null) return false;
        redis.delete(key); // 一次性验证码，用后即焚
        return stored.equalsIgnoreCase(code.trim());
    }
}
