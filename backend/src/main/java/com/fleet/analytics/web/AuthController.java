package com.fleet.analytics.web;

import com.fleet.analytics.auth.AuthenticationService;
import com.fleet.analytics.security.TenantAuthenticationToken;
import com.fleet.analytics.security.TokenRevocationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authentication;
    private final TokenRevocationService revocations;

    public AuthController(AuthenticationService authentication, TokenRevocationService revocations) {
        this.authentication = authentication;
        this.revocations = revocations;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        return authentication.authenticate(request);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(TenantAuthenticationToken authentication) {
        revocations.revoke(authentication.getCredentials());
    }
}
