package com.tripsync.domain.enums

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

@Converter(autoApply = true)
class YnFlagConverter : AttributeConverter<YnFlag, String> {
    override fun convertToDatabaseColumn(attribute: YnFlag?): String? {
        return attribute?.name
    }

    override fun convertToEntityAttribute(dbData: String?): YnFlag? {
        return dbData?.let { YnFlag.valueOf(it) }
    }
}
