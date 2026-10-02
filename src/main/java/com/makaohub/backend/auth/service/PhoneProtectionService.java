package com.makaohub.backend.auth.service;

import com.makaohub.backend.auth.config.PhoneSecurityProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

@Service
public class PhoneProtectionService {

    private static final String ENCRYPTION_ALGORITHM =
            "AES/GCM/NoPadding";

    private static final String LOOKUP_ALGORITHM =
            "HmacSHA256";

    private static final int KEY_LENGTH_BYTES = 32;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final byte FORMAT_VERSION = 1;

    private static final byte[] ADDITIONAL_AUTHENTICATED_DATA =
            "makao-hub:phone:v1"
                    .getBytes(StandardCharsets.UTF_8);

    private final SecretKey encryptionKey;
    private final SecretKey lookupHmacKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public PhoneProtectionService(
            PhoneSecurityProperties properties
    ) {
        this.encryptionKey = new SecretKeySpec(
                decodeKey(
                        properties.encryptionKey(),
                        "PHONE_ENCRYPTION_KEY"
                ),
                "AES"
        );

        this.lookupHmacKey = new SecretKeySpec(
                decodeKey(
                        properties.lookupHmacKey(),
                        "PHONE_LOOKUP_HMAC_KEY"
                ),
                LOOKUP_ALGORITHM
        );
    }

    public ProtectedPhone protect(String normalizedPhoneNumber) {
        byte[] plaintext = Objects.requireNonNull(
                normalizedPhoneNumber
        ).getBytes(StandardCharsets.UTF_8);

        try {
            byte[] initializationVector =
                    new byte[IV_LENGTH_BYTES];

            secureRandom.nextBytes(initializationVector);

            Cipher cipher = Cipher.getInstance(
                    ENCRYPTION_ALGORITHM
            );

            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    encryptionKey,
                    new GCMParameterSpec(
                            GCM_TAG_LENGTH_BITS,
                            initializationVector
                    )
            );

            cipher.updateAAD(ADDITIONAL_AUTHENTICATED_DATA);

            byte[] encryptedPhone = cipher.doFinal(plaintext);

            ByteBuffer payload = ByteBuffer.allocate(
                    1
                            + initializationVector.length
                            + encryptedPhone.length
            );

            payload.put(FORMAT_VERSION);
            payload.put(initializationVector);
            payload.put(encryptedPhone);

            return new ProtectedPhone(
                    payload.array(),
                    createLookupHash(plaintext)
            );
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Phone-number protection failed.",
                    exception
            );
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public String decrypt(byte[] protectedPhone) {
        byte[] payload = Objects.requireNonNull(
                protectedPhone
        ).clone();

        if (payload.length
                < 1 + IV_LENGTH_BYTES + GCM_TAG_LENGTH_BITS / 8) {
            throw new IllegalArgumentException(
                    "Invalid encrypted phone payload."
            );
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        byte formatVersion = buffer.get();

        if (formatVersion != FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported encrypted phone format."
            );
        }

        byte[] initializationVector =
                new byte[IV_LENGTH_BYTES];

        buffer.get(initializationVector);

        byte[] encryptedPhone =
                new byte[buffer.remaining()];

        buffer.get(encryptedPhone);

        try {
            Cipher cipher = Cipher.getInstance(
                    ENCRYPTION_ALGORITHM
            );

            cipher.init(
                    Cipher.DECRYPT_MODE,
                    encryptionKey,
                    new GCMParameterSpec(
                            GCM_TAG_LENGTH_BITS,
                            initializationVector
                    )
            );

            cipher.updateAAD(ADDITIONAL_AUTHENTICATED_DATA);

            byte[] plaintext = cipher.doFinal(encryptedPhone);

            try {
                return new String(
                        plaintext,
                        StandardCharsets.UTF_8
                );
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Phone-number decryption failed.",
                    exception
            );
        }
    }

    private String createLookupHash(byte[] plaintext)
            throws GeneralSecurityException {

        Mac mac = Mac.getInstance(LOOKUP_ALGORITHM);
        mac.init(lookupHmacKey);

        return HexFormat.of().formatHex(
                mac.doFinal(plaintext)
        );
    }

    private static byte[] decodeKey(
            String encodedKey,
            String variableName
    ) {
        byte[] decodedKey;

        try {
            decodedKey = Base64.getDecoder()
                    .decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    variableName + " must be valid Base64.",
                    exception
            );
        }

        if (decodedKey.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    variableName
                            + " must decode to exactly 32 bytes."
            );
        }

        return decodedKey;
    }

    public record ProtectedPhone(
            byte[] ciphertext,
            String lookupHash
    ) {
        public ProtectedPhone {
            ciphertext = ciphertext.clone();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }
    }
}
