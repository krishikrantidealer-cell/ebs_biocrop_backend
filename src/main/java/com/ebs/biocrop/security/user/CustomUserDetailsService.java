package com.ebs.biocrop.security.user;

import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
        User user = identifier != null && identifier.contains("@")
                ? userRepository.findByEmailIgnoreCase(identifier)
                    .orElseThrow(() -> new UsernameNotFoundException("Account not found"))
                : userRepository.findByPhoneNumber(identifier)
                    .orElseThrow(() -> new UsernameNotFoundException("Account not found"));
        return CustomUserDetails.build(user);
    }
}
