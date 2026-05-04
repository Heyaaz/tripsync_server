package com.tripsync.domain.repository

import com.tripsync.domain.entity.User
import com.tripsync.domain.enums.AuthProvider
import com.tripsync.domain.enums.YnFlag
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.*

@Repository
interface UserRepository : JpaRepository<User, Long> {
    fun findByEmailAndDelYn(email: String, delYn: YnFlag): User?
    fun findByAuthProviderAndProviderUserId(authProvider: AuthProvider, providerUserId: String): User?
    fun existsByNickname(nickname: String): Boolean
}
