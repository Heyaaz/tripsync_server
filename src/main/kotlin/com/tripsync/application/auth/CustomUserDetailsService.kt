package com.tripsync.application.auth

import com.tripsync.domain.entity.User
import com.tripsync.domain.repository.UserRepository
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

@Service
class CustomUserDetailsService(
    private val userRepository: UserRepository,
) : UserDetailsService {

    override fun loadUserByUsername(username: String): UserDetails {
        val user = userRepository.findById(username.toLong())
            .orElseThrow { UsernameNotFoundException("User not found: $username") }

        return org.springframework.security.core.userdetails.User.builder()
            .username(user.id.toString())
            .password(user.passwordHash ?: "")
            .authorities(if (user.adminYn.name == "Y") "ROLE_ADMIN" else "ROLE_USER")
            .build()
    }

    fun loadUserEntity(userId: Long): User {
        return userRepository.findById(userId)
            .orElseThrow { UsernameNotFoundException("User not found: $userId") }
    }
}
