package com.nisaba.config

import com.nisaba.persistence.entity.TorrentState
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration

/**
 * Registers custom converters for mapping between Kotlin enums and
 * database VARCHAR columns used in Spring Data JDBC.
 */
@Configuration
class JdbcConfig : AbstractJdbcConfiguration() {

    override fun userConverters(): List<Any> = listOf(
        TorrentStateReadConverter(),
        TorrentStateWriteConverter()
    )

    /**
     * Reads a VARCHAR state value from the database
     * and converts it to a Kotlin [TorrentState] enum constant.
     */
    @ReadingConverter
    class TorrentStateReadConverter : Converter<String, TorrentState> {
        override fun convert(source: String): TorrentState =
            TorrentState.valueOf(source.uppercase())
    }

    /**
     * Writes a Kotlin [TorrentState] enum constant as a lowercase
     * string for the database VARCHAR column.
     */
    @WritingConverter
    class TorrentStateWriteConverter : Converter<TorrentState, String> {
        override fun convert(source: TorrentState): String =
            source.name.lowercase()
    }
}
