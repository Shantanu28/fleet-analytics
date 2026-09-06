package com.fleet.analytics.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Shared bootstrapping for tests that drive the real filter chain over the real database. The
 * security configurer is applied here so no test can accidentally exercise the controllers with
 * authentication switched off.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestBase extends PostgresTestBase {

    @Autowired
    private WebApplicationContext webApplicationContext;

    protected MockMvc mvc;

    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }
}
