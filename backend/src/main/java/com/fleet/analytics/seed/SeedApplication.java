package com.fleet.analytics.seed;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Profiles;

/** Separate explicit command: no component scan, HTTP server, JWT/HMAC config or auto-migration. */
public final class SeedApplication {
    private static final Logger LOG = LoggerFactory.getLogger(SeedApplication.class);

    private SeedApplication() {}

    public static void main(String[] args) throws Exception {
        try (var context = new SpringApplicationBuilder(SeedConfiguration.class)
                .web(WebApplicationType.NONE).run(args)) {
            if (!context.getEnvironment().acceptsProfiles(Profiles.of("seed"))) {
                throw new IllegalStateException("The explicit seed profile is required");
            }
            var outcome = new SeedInstaller(context.getBean(DataSource.class)).install();
            LOG.info("Demo seed: {}", outcome);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("seed")
    @Import(DataSourceAutoConfiguration.class)
    static class SeedConfiguration {}
}
