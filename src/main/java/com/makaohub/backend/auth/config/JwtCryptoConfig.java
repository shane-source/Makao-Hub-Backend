package com.makaohub.backend.auth.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Configuration
public class JwtCryptoConfig {

    @Bean
    RSAPrivateKey jwtPrivateKey(
            JwtSecurityProperties properties
    ) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(
                    properties.privateKey()
            );

            KeyFactory keyFactory = KeyFactory.getInstance("RSA");

            return (RSAPrivateKey) keyFactory.generatePrivate(
                    new PKCS8EncodedKeySpec(keyBytes)
            );
        } catch (
                IllegalArgumentException
                | GeneralSecurityException exception
        ) {
            throw new IllegalStateException(
                    "JWT private key is invalid.",
                    exception
            );
        }
    }

    @Bean
    RSAPublicKey jwtPublicKey(
            JwtSecurityProperties properties
    ) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(
                    properties.publicKey()
            );

            KeyFactory keyFactory = KeyFactory.getInstance("RSA");

            return (RSAPublicKey) keyFactory.generatePublic(
                    new X509EncodedKeySpec(keyBytes)
            );
        } catch (
                IllegalArgumentException
                | GeneralSecurityException exception
        ) {
            throw new IllegalStateException(
                    "JWT public key is invalid.",
                    exception
            );
        }
    }

    @Bean
    JwtEncoder jwtEncoder(
            RSAPublicKey publicKey,
            RSAPrivateKey privateKey,
            JwtSecurityProperties properties
    ) {
        verifyMatchingKeyPair(publicKey, privateKey);

        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .keyID(properties.keyId())
                .build();

        JWKSource<SecurityContext> keySource =
                new ImmutableJWKSet<>(new JWKSet(rsaKey));

        return new NimbusJwtEncoder(keySource);
    }

    @Bean
    JwtDecoder jwtDecoder(
            RSAPublicKey publicKey,
            JwtSecurityProperties properties
    ) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withPublicKey(publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();

        OAuth2TokenValidator<Jwt> issuerValidator =
                JwtValidators.createDefaultWithIssuer(
                        properties.issuer()
                );

        OAuth2TokenValidator<Jwt> audienceValidator =
                new JwtAudienceValidator(properties.audience());

        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        issuerValidator,
                        audienceValidator
                )
        );

        return decoder;
    }

    private static void verifyMatchingKeyPair(
            RSAPublicKey publicKey,
            RSAPrivateKey privateKey
    ) {
        if (!publicKey.getModulus().equals(
                privateKey.getModulus()
        )) {
            throw new IllegalStateException(
                    "JWT public and private keys do not match."
            );
        }
    }
}