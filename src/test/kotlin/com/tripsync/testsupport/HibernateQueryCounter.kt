package com.tripsync.testsupport

import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory

class HibernateQueryCounter(entityManagerFactory: EntityManagerFactory) {
    private val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics

    fun <T> count(block: () -> T): QueryCountResult<T> {
        statistics.isStatisticsEnabled = true
        statistics.clear()
        val result = block()
        return QueryCountResult(
            result = result,
            prepareStatementCount = statistics.prepareStatementCount,
        )
    }
}

data class QueryCountResult<T>(
    val result: T,
    val prepareStatementCount: Long,
)
