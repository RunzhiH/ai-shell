/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.util;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.prefs.Preferences;

/**
 * 密码加密工具类
 * 提供密码加密和解密功能，保护用户敏感信息
 */
@Slf4j
public class PasswordEncryptionUtil {
    
    private static final String KEY_ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String LEGACY_TRANSFORMATION = "AES/ECB/PKCS5Padding";
    private static final String LEGACY_KEY = "AIShellInitKey2023";
    private static final String FORMAT_PREFIX = "v2:";
    private static final String KEY_PREFERENCE = "credential-encryption-key-v2";
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(PasswordEncryptionUtil.class);
    private static volatile SecretKey cachedSecretKey;
    
    /**
     * 加密密码
     * 
     * @param password 明文密码
     * @return 加密后的密码（Base64编码）
     */
    public static String encryptPassword(String password) {
        log.debug("开始加密密码");
        if (password == null || password.isEmpty()) {
            log.debug("密码为空，跳过加密");
            return password;
        }
        
        try {
            SecretKey secretKey = getOrCreateSecretKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            byte[] nonce = new byte[NONCE_LENGTH];
            SECURE_RANDOM.nextBytes(nonce);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            
            byte[] encryptedBytes = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[nonce.length + encryptedBytes.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(encryptedBytes, 0, payload, nonce.length, encryptedBytes.length);
            String encrypted = FORMAT_PREFIX + Base64.getEncoder().encodeToString(payload);
            log.debug("密码加密成功，加密后长度: {}", encrypted.length());
            return encrypted;
        } catch (Exception e) {
            log.error("Failed to encrypt password: {}", e.getMessage(), e);
            throw new IllegalStateException("Unable to encrypt password", e);
        }
    }
    
    /**
     * 解密密码
     * 
     * @param encryptedPassword 加密后的密码（Base64编码）
     * @return 解密后的明文密码
     */
    public static String decryptPassword(String encryptedPassword) {

        if (encryptedPassword == null || encryptedPassword.isEmpty()) {

            return encryptedPassword;
        }
        
        try {
            if (encryptedPassword.startsWith(FORMAT_PREFIX)) {
                byte[] payload = Base64.getDecoder().decode(encryptedPassword.substring(FORMAT_PREFIX.length()));
                if (payload.length <= NONCE_LENGTH) {
                    throw new IllegalArgumentException("Invalid encrypted password payload");
                }
                byte[] nonce = java.util.Arrays.copyOfRange(payload, 0, NONCE_LENGTH);
                byte[] ciphertext = java.util.Arrays.copyOfRange(payload, NONCE_LENGTH, payload.length);
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(),
                        new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
                return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
            }

            // 兼容读取旧版密文；下一次保存时会自动升级为 v2 格式。
            if (isValidBase64(encryptedPassword)) {
                log.warn("检测到旧版 AES/ECB 加密格式，自动解密中。下次保存将升级为 v2 安全格式。");
                Cipher legacyCipher = Cipher.getInstance(LEGACY_TRANSFORMATION);
                legacyCipher.init(Cipher.DECRYPT_MODE, getLegacySecretKey());
                String decrypted = new String(legacyCipher.doFinal(Base64.getDecoder().decode(encryptedPassword)),
                        StandardCharsets.UTF_8);
                log.info("旧版密文已解密，建议重新保存配置以升级加密格式");
                return decrypted;
            }
            throw new IllegalArgumentException("Password is neither plaintext nor a supported encrypted value");
        } catch (Exception e) {
            log.error("Failed to decrypt password: {}", e.getMessage(), e);
            throw new IllegalArgumentException("Unable to decrypt password", e);
        }
    }
    
    /**
     * 检查是否是有效的Base64字符串
     */
    private static boolean isValidBase64(String base64String) {
        try {
            Base64.getDecoder().decode(base64String);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
    
    /**
     * 获取或创建加密密钥
     * 
     * @return AES密钥
     */
    private static synchronized SecretKey getOrCreateSecretKey() {
        if (cachedSecretKey != null) {
            return cachedSecretKey;
        }
        String encodedKey = PREFERENCES.get(KEY_PREFERENCE, null);
        if (encodedKey == null) {
            byte[] keyBytes = new byte[32];
            SECURE_RANDOM.nextBytes(keyBytes);
            encodedKey = Base64.getEncoder().encodeToString(keyBytes);
            PREFERENCES.put(KEY_PREFERENCE, encodedKey);
        }
        cachedSecretKey = new SecretKeySpec(Base64.getDecoder().decode(encodedKey), KEY_ALGORITHM);
        return cachedSecretKey;
    }

    private static SecretKey getLegacySecretKey() {
        byte[] keyBytes = new byte[16];
        byte[] seedBytes = LEGACY_KEY.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < keyBytes.length; i++) {
            keyBytes[i] = seedBytes[i % seedBytes.length];
        }
        return new SecretKeySpec(keyBytes, KEY_ALGORITHM);
    }
}
