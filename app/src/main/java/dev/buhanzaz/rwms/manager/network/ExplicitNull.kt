package dev.buhanzaz.rwms.manager.network

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonQualifier
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.lang.reflect.Type

/**
 * Serializes a nullable property as an explicit JSON null without changing
 * Moshi's default omission rule for every other nullable property.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.VALUE_PARAMETER,
)
@JsonQualifier
annotation class ExplicitNull

object ExplicitNullJsonAdapterFactory : JsonAdapter.Factory {
    override fun create(
        type: Type,
        annotations: Set<Annotation>,
        moshi: Moshi,
    ): JsonAdapter<*>? {
        val delegateAnnotations = Types.nextAnnotations(
            annotations,
            ExplicitNull::class.java,
        ) ?: return null
        return moshi.nextAdapter<Any>(
            this,
            type,
            delegateAnnotations,
        ).serializeNulls()
    }
}
