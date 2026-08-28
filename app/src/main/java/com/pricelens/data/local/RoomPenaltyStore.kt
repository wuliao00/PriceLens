package com.pricelens.data.local

import com.pricelens.data.local.dao.DomainPenaltyDao
import com.pricelens.data.local.entity.DomainPenaltyEntity
import com.pricelens.util.PenaltyStore

/**
 * §阶段3 熔断持久化的 Room 实现（域名 → 解封时间戳小表）。
 * 由 AppModule 注入 [RateLimiter]，进程重启后首次过闸时惰性恢复。
 */
class RoomPenaltyStore(private val dao: DomainPenaltyDao) : PenaltyStore {

    override suspend fun loadActive(nowMs: Long): Map<String, Long> = dao.loadActive(nowMs).associate { it.domain to it.untilMs }

    override suspend fun save(domain: String, untilMs: Long) {
        dao.upsert(DomainPenaltyEntity(domain = domain, untilMs = untilMs))
    }

    override suspend fun cleanup(nowMs: Long) {
        dao.deleteExpired(nowMs)
    }
}
