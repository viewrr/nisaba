package com.nisaba.persistence.repository

import com.nisaba.persistence.entity.BandwidthSampleEntity
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository

interface BandwidthSampleRepository : CrudRepository<BandwidthSampleEntity, Long> {
    @Query("SELECT COUNT(*) FROM bandwidth_samples WHERE sampled_at > now() - INTERVAL '48 hours'")
    fun countRecentSamples(): Long
}
