package com.ebs.biocrop.security.jwt;

import com.ebs.biocrop.security.user.CustomUserDetailsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtTokenProvider tokenProvider;
    private final CustomUserDetailsService userDetailsService;
    private final AuthenticationEntryPoint authenticationEntryPoint;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider, CustomUserDetailsService userDetailsService,
                                   AuthenticationEntryPoint authenticationEntryPoint) {
        this.tokenProvider = tokenProvider;
        this.userDetailsService = userDetailsService;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith("/api/v1/webhooks/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String jwt = getJwtFromRequest(request);
        if (jwt != null) {
            if (!StringUtils.hasText(jwt) || !tokenProvider.validateToken(jwt) || !tokenProvider.isAccessToken(jwt)) {
                rejectInvalidToken(request, response);
                return;
            }
            try {
                String userId = tokenProvider.getUserIdFromToken(jwt);
                UserDetails userDetails = (userId != null && !userId.isBlank())
                        ? userDetailsService.loadUserById(userId)
                        : userDetailsService.loadUserByUsername(tokenProvider.getPhoneNumberFromToken(jwt));
                if (!userDetails.isEnabled()) {
                    rejectInvalidToken(request, response);
                    return;
                }
                if (userDetails instanceof com.ebs.biocrop.security.user.CustomUserDetails customUser
                        && tokenProvider.getTokenVersionFromToken(jwt) != customUser.getTokenVersion()) {
                    rejectInvalidToken(request, response);
                    return;
                }
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception ex) {
                log.warn("Could not authenticate supplied bearer token for {}", request.getRequestURI());
                rejectInvalidToken(request, response);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private void rejectInvalidToken(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        SecurityContextHolder.clearContext();
        authenticationEntryPoint.commence(request, response,
                new BadCredentialsException("A valid access token is required when an Authorization bearer token is supplied."));
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken)) {
            if (!bearerToken.startsWith("Bearer ")) return "";
            return bearerToken.substring(7).trim();
        }
        return null;
    }
}
