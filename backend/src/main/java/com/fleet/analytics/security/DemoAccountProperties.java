package com.fleet.analytics.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Demo accounts are inert unless explicitly enabled. This flag is only half the control: the demo
 * profile must also be active ({@link DemoAccountPolicy}). Development key generation is a wholly
 * separate switch and never enables demo accounts.
 */
@ConfigurationProperties(prefix = "fleet.demo")
public record DemoAccountProperties(boolean accountsEnabled) {}
