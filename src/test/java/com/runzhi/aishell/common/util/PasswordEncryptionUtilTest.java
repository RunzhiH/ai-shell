package com.runzhi.aishell.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordEncryptionUtilTest {

    @Test
    void encryptsAndDecryptsWithAuthenticatedFormat() {
        String encrypted = PasswordEncryptionUtil.encryptPassword("correct horse battery staple");

        assertTrue(encrypted.startsWith("v2:"));
        assertEquals("correct horse battery staple", PasswordEncryptionUtil.decryptPassword(encrypted));
    }

    @Test
    void usesRandomNonceForEachEncryption() {
        String first = PasswordEncryptionUtil.encryptPassword("same-password");
        String second = PasswordEncryptionUtil.encryptPassword("same-password");

        assertNotEquals(first, second);
    }

    @Test
    void rejectsTamperedCiphertext() {
        String encrypted = PasswordEncryptionUtil.encryptPassword("secret");
        int last = encrypted.length() - 1;
        char replacement = encrypted.charAt(last) == 'A' ? 'B' : 'A';
        String tampered = encrypted.substring(0, last) + replacement;

        assertThrows(IllegalArgumentException.class,
                () -> PasswordEncryptionUtil.decryptPassword(tampered));
    }

    @Test
    void encryptedOutputStartsWithV2Prefix() {
        String encrypted = PasswordEncryptionUtil.encryptPassword("any-password");

        assertTrue(encrypted.startsWith("v2:"),
                "encrypted text should start with 'v2:' prefix");
    }

    @Test
    void encryptNullReturnsNull() {
        assertNull(PasswordEncryptionUtil.encryptPassword(null));
    }

    @Test
    void encryptEmptyStringReturnsEmptyString() {
        assertEquals("", PasswordEncryptionUtil.encryptPassword(""));
    }

    @Test
    void encryptAndDecryptPreservesChineseCharacters() {
        String chinese = "你好世界，密码测试！";

        String encrypted = PasswordEncryptionUtil.encryptPassword(chinese);
        String decrypted = PasswordEncryptionUtil.decryptPassword(encrypted);

        assertEquals(chinese, decrypted);
    }
}
