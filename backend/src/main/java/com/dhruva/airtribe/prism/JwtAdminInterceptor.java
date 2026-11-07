package com.dhruva.airtribe.prism;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.cors.CorsUtils;

import java.io.IOException;

@Component
public class JwtAdminInterceptor implements HandlerInterceptor {
    private final AuthService auth;
    public JwtAdminInterceptor(AuthService auth) { this.auth = auth; }

    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (CorsUtils.isPreFlightRequest(request)) return true;
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ") && auth.isValidToken(header.substring(7).trim())) return true;
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":{\"message\":\"A valid sign-in token is required.\"}}");
        return false;
    }
}
