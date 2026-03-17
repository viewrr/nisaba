package com.nisaba.persistence.repository

import com.nisaba.persistence.entity.TorrentEntity
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import java.time.Instant

interface TorrentRepository : CrudRepository<TorrentEntity, String> {
    fun findByInfohash(infohash: String): TorrentEntity?
    fun findByCategory(category: String): List<TorrentEntity>

    @Query("SELECT * FROM torrents WHERE state = CAST(:state AS torrent_state)")
    fun findByState(state: String): List<TorrentEntity>

    @Query("SELECT * FROM torrents WHERE state = ANY(CAST(:states AS torrent_state[]))")
    fun findByStateIn(states: Array<String>): List<TorrentEntity>

    @Modifying
    @Query("""
        UPDATE torrents SET 
            progress_pct = :progress, 
            last_synced_at = :syncedAt,
            total_size = COALESCE(:totalSize, total_size),
            content_path = COALESCE(:contentPath, content_path),
            eta = :eta,
            ratio = :ratio,
            seeding_time = :seedingTime
        WHERE infohash = :infohash
    """)
    fun updateProgress(
        infohash: String,
        progress: Float,
        syncedAt: Instant = Instant.now(),
        totalSize: Long? = null,
        contentPath: String? = null,
        eta: Long? = null,
        ratio: Float? = null,
        seedingTime: Long? = null
    )
}
