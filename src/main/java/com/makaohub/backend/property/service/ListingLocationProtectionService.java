package com.makaohub.backend.property.service;

import com.makaohub.backend.property.config.LocationSecurityProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Service
public class ListingLocationProtectionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final byte FORMAT_VERSION = 1;

    private static final byte[] AAD =
            "makao-hub:listing-location:v1"
                    .getBytes(StandardCharsets.UTF_8);

    private final SecretKey encryptionKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public ListingLocationProtectionService(
            LocationSecurityProperties properties
    ) {
        this.encryptionKey = new SecretKeySpec(
                decodeKey(properties.encryptionKey()),
                "AES"
        );
    }

    public byte[] encryptCoordinates(
            BigDecimal latitude,
            BigDecimal longitude
    ) {
        return encrypt(
                latitude.toPlainString()
                        + ","
                        + longitude.toPlainString()
        );
    }

    public ExactCoordinates decryptCoordinates(byte[] payload) {
        String plaintext = decrypt(payload);
        String[] values = plaintext.split(",", -1);

        if (values.length != 2) {
            throw new IllegalStateException(
                    "Invalid encrypted location payload."
            );
        }

        try {
            return new ExactCoordinates(
                    new BigDecimal(values[0]),
                    new BigDecimal(values[1])
            );
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "Invalid encrypted location payload.",
                    exception
            );
        }
    }

    private byte[] encrypt(String value) {
        byte[] plaintext = value.getBytes(StandardCharsets.UTF_8);

        try {
            byte[] initializationVector = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(initializationVector);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    encryptionKey,
                    new GCMParameterSpec(
                            GCM_TAG_LENGTH_BITS,
                            initializationVector
                    )
            );
            cipher.updateAAD(AAD);

            byte[] encrypted = cipher.doFinal(plaintext);

            ByteBuffer payload = ByteBuffer.allocate(
                    1 + initializationVector.length + encrypted.length
            );

            payload.put(FORMAT_VERSION);
            payload.put(initializationVector);
            payload.put(encrypted);

            return payload.array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Location encryption failed.",
                    exception
            );
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private String decrypt(byte[] protectedValue) {
        byte[] payload = protectedValue.clone();

        if (payload.length
                < 1 + IV_LENGTH_BYTES + GCM_TAG_LENGTH_BITS / 8) {
            throw new IllegalStateException(
                    "Invalid encrypted location payload."
            );
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);

        if (buffer.get() != FORMAT_VERSION) {
            throw new IllegalStateException(
                    "Unsupported encrypted location format."
            );
        }

        byte[] initializationVector = new byte[IV_LENGTH_BYTES];
        buffer.get(initializationVector);

        byte[] encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);

        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    encryptionKey,
                    new GCMParameterSpec(
                            GCM_TAG_LENGTH_BITS,
                            initializationVector
                    )
            );
            cipher.updateAAD(AAD);

            byte[] plaintext = cipher.doFinal(encrypted);

            try {
                return new String(plaintext, StandardCharsets.UTF_8);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Location decryption failed.",
                    exception
            );
        }
    }

    private static byte[] decodeKey(String encodedKey) {
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedKey);

            if (decoded.length != KEY_LENGTH_BYTES) {
                throw new IllegalStateException(
                        "LOCATION_ENCRYPTION_KEY must decode to exactly 32 bytes."
                );
            }

            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "LOCATION_ENCRYPTION_KEY must be valid Base64.",
                    exception
            );
        }
    }

    public record ExactCoordinates(
            BigDecimal latitude,
            BigDecimal longitude
    ) {
    }
}