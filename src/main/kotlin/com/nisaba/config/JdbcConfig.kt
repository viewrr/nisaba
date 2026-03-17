package com.nisaba.config

import com.nisaba.persistence.entity.TorrentState
import org.postgresql.util.PGobject
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration

/**
 * Registers custom converters for mapping between Kotlin enums and
 * PostgreSQL custom enum types used in Spring Data JDBC.
 */
@Configuration
class JdbcConfig : AbstractJdbcConfiguration() {

    override fun userConverters(): List<Any> = listOf(
        TorrentStateReadConverter(),
        TorrentStateWriteConverter()
    )

    /**
     * Reads a PostgreSQL `torrent_state` enum value (delivered as PGobject)
     * and converts it to a Kotlin [TorrentState] enum constant.
     */
    @ReadingConverter
    class TorrentStateReadConverter : Converter<PGobject, TorrentState> {
        override fun convert(source: PGobject): TorrentState =
            TorrentState.valueOf(source.value!!.uppercase())
    }

    /**
     * Writes a Kotlin [TorrentState] enum constant as a PostgreSQL
     * `torrent_state` enum value wrapped in a PGobject.
     */
    @WritingConverter
    class TorrentStateWriteConverter : Converter<TorrentState, PGobject> {
        override fun convert(source: TorrentState): PGobject =
            PGobject().apply {
                type = "torrent_state"
                value = source.name.lowercase()
            }
    }
}
