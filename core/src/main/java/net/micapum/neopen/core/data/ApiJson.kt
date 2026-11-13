package net.micapum.neopen.core.data

import com.squareup.moshi.*
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.lang.reflect.Type

@Retention(AnnotationRetention.RUNTIME)
@JsonQualifier
annotation class ArgbColor

/** El SDK web entrega uint32; Android representa los mismos bits como int32. */
class ArgbColorAdapter {
    @FromJson @ArgbColor fun fromJson(value: Long?): Int? {
        if (value == null) return null
        if (value !in Int.MIN_VALUE.toLong()..0xffffffffL) throw JsonDataException("ARGB fuera de 32 bits")
        return value.toInt()
    }
    @ToJson fun toJson(@ArgbColor value: Int?): Long? = value?.toLong()
}

/** Distingue una propiedad omitida de un borrado explícito sin emitir otros nulls. */
private object CapturePatchAdapterFactory : JsonAdapter.Factory {
    override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
        if (type != CapturePatchDto::class.java || annotations.isNotEmpty()) return null
        val delegate = moshi.nextAdapter<CapturePatchDto>(this, type, annotations)
        val mapAdapter = moshi.adapter<Map<String, Any?>>(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))
        return object : JsonAdapter<CapturePatchDto>() {
            override fun fromJson(reader: JsonReader): CapturePatchDto? {
                val fields = mapAdapter.fromJson(reader) ?: return null
                return delegate.fromJsonValue(fields)?.copy(paperSpecified = fields.containsKey("paper"))
            }
            override fun toJson(writer: JsonWriter, value: CapturePatchDto?) {
                if (value == null) { writer.nullValue(); return }
                @Suppress("UNCHECKED_CAST")
                val fields = (delegate.toJsonValue(value) as Map<String, Any?>).toMutableMap()
                if (value.paperSpecified && value.paper == null) fields["paper"] = null
                val previous = writer.serializeNulls
                writer.serializeNulls = true
                try { mapAdapter.toJson(writer, fields) } finally { writer.serializeNulls = previous }
            }
        }
    }
}

/** El almacenamiento del outbox y Retrofit deben usar exactamente el mismo contrato. */
private val sharedApiMoshi: Moshi by lazy {
    Moshi.Builder()
        .add(ArgbColorAdapter())
        .add(CapturePatchAdapterFactory)
        .addLast(KotlinJsonAdapterFactory())
        .build()
}

fun apiMoshi(): Moshi = sharedApiMoshi
