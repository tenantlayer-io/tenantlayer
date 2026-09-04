package com.acme.orders;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes /t/{tenant}/orders reach the same controller as /orders.
 *
 * FINDING from dogfooding: TenantLayer's path-segment resolver (feature 3) reads the
 * tenant out of the URL, but nothing then routes that URL to the application's handlers —
 * so every /t/... request 404s unless the app either maps duplicate routes or rewrites,
 * as here. The feature is only half usable without this, and the matrix does not list the
 * other half. It belongs in the library.
 *
 * Runs after TenantLayer's filter has already resolved the tenant, and forwards rather
 * than redirects so the resolved context survives.
 */
@Component
public class TenantPathRewriteFilter extends OncePerRequestFilter implements Ordered {

    private static final String PREFIX = "/t/";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 30;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith(PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        int afterTenant = path.indexOf('/', PREFIX.length());
        String remainder = afterTenant < 0 ? "/" : path.substring(afterTenant);
        request.getRequestDispatcher(remainder).forward(request, response);
    }
}
