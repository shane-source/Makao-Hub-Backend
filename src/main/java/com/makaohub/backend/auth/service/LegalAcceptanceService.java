package com.makaohub.backend.auth.service;

import com.makaohub.backend.auth.domain.LegalAcceptance;
import com.makaohub.backend.auth.domain.LegalDocumentType;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.LegalAcceptanceRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LegalAcceptanceService {

    private static final String TERMS_VERSION = "1.0";
    private static final String PRIVACY_VERSION = "1.0";

    private final LegalAcceptanceRepository repository;

    public LegalAcceptanceService(
            LegalAcceptanceRepository repository
    ) {
        this.repository = repository;
    }

    public void recordCurrentDocuments(User user) {
        repository.saveAll(
                List.of(
                        new LegalAcceptance(
                                user,
                                LegalDocumentType.TERMS_OF_SERVICE,
                                TERMS_VERSION
                        ),
                        new LegalAcceptance(
                                user,
                                LegalDocumentType.PRIVACY_POLICY,
                                PRIVACY_VERSION
                        )
                )
        );
    }
}