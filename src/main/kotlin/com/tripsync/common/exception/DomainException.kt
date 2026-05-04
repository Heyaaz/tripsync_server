package com.tripsync.common.exception

import org.springframework.http.HttpStatus

class DomainException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
) : RuntimeException(message)
