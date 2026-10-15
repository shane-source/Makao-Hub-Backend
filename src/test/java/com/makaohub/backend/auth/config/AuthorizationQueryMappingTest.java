package com.makaohub.backend.auth.config;

import com.makaohub.backend.auth.repository.UserRoleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class AuthorizationQueryMappingTest {
    @Test
    void authorizationQueryCompilesAgainstTheJpaModelWithoutADatabase() {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setPackagesToScan("com.makaohub.backend.auth.domain");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of(
                "hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect",
                "hibernate.boot.allow_jdbc_metadata_access", "false",
                "hibernate.hbm2ddl.auto", "none"
        ));
        try {
            factory.afterPropertiesSet();
            try (var em = Objects.requireNonNull(factory.getObject()).createEntityManager()) {
                assertDoesNotThrow(() -> new JpaRepositoryFactory(em).getRepository(UserRoleRepository.class));
            }
        } finally {
            factory.destroy();
        }
    }
}
