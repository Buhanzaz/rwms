package dev.buhanzaz.rwms.worker.core.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Encodes the exact public `WorkerActionRequest` object from
 * `contracts/openapi/task-board-service.yaml`.
 *
 * The application deliberately omits ordinary nullable values from JSON, but this one canonical
 * command declares `workerGroupId` and `evidenceId` as required nullable properties. Writing a
 * [JsonObject] directly keeps those two keys explicit without changing serialization for cached
 * projections, Problem Details, media commands, or any other transport type. Decoding rejects
 * missing and additional properties even when the surrounding [kotlinx.serialization.json.Json]
 * instance tolerates compatible additions for response projections.
 */
object WorkerActionRequestDtoSerializer : KSerializer<WorkerActionRequestDto> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "dev.buhanzaz.rwms.worker.core.network.WorkerActionRequestDto",
    ) {
        element<String>("operationId")
        element<String>("action")
        element<Long>("expectedVersion")
        element<String?>("workerGroupId")
        element<String?>("evidenceId")
        element<String>("occurredAt")
        element<String>("offlineLeaseId")
    }

    override fun serialize(encoder: Encoder, value: WorkerActionRequestDto) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("WorkerActionRequestDto supports JSON only")
        jsonEncoder.encodeJsonElement(
            buildJsonObject {
                put("operationId", JsonPrimitive(value.operationId))
                put("action", JsonPrimitive(value.action))
                put("expectedVersion", JsonPrimitive(value.expectedVersion))
                put("workerGroupId", value.workerGroupId?.let(::JsonPrimitive) ?: JsonNull)
                put("evidenceId", value.evidenceId?.let(::JsonPrimitive) ?: JsonNull)
                put("occurredAt", JsonPrimitive(value.occurredAt))
                put("offlineLeaseId", JsonPrimitive(value.offlineLeaseId))
            },
        )
    }

    override fun deserialize(decoder: Decoder): WorkerActionRequestDto {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("WorkerActionRequestDto supports JSON only")
        val payload = jsonDecoder.decodeJsonElement() as? JsonObject
            ?: throw SerializationException("WorkerActionRequestDto must be a JSON object")
        if (payload.keys != WORKER_ACTION_REQUEST_FIELDS) {
            throw SerializationException(
                "WorkerActionRequestDto must contain exactly ${WORKER_ACTION_REQUEST_FIELDS.joinToString()}",
            )
        }
        return WorkerActionRequestDto(
            operationId = payload.requiredString("operationId"),
            action = payload.requiredString("action"),
            expectedVersion = payload.requiredLong("expectedVersion"),
            workerGroupId = payload.requiredNullableString("workerGroupId"),
            evidenceId = payload.requiredNullableString("evidenceId"),
            occurredAt = payload.requiredString("occurredAt"),
            offlineLeaseId = payload.requiredString("offlineLeaseId"),
        )
    }
}

private val WORKER_ACTION_REQUEST_FIELDS = setOf(
    "operationId",
    "action",
    "expectedVersion",
    "workerGroupId",
    "evidenceId",
    "occurredAt",
    "offlineLeaseId",
)

private fun JsonObject.requiredString(name: String): String {
    val primitive = this[name] as? JsonPrimitive
        ?: throw SerializationException("WorkerActionRequestDto.$name must be a string")
    if (!primitive.isString) {
        throw SerializationException("WorkerActionRequestDto.$name must be a string")
    }
    return primitive.content
}

private fun JsonObject.requiredLong(name: String): Long {
    val primitive = this[name] as? JsonPrimitive
        ?: throw SerializationException("WorkerActionRequestDto.$name must be an integer")
    if (primitive.isString) {
        throw SerializationException("WorkerActionRequestDto.$name must be an integer")
    }
    return primitive.longOrNull
        ?: throw SerializationException("WorkerActionRequestDto.$name must be an integer")
}

private fun JsonObject.requiredNullableString(name: String): String? {
    val value = this[name]
        ?: throw SerializationException("WorkerActionRequestDto.$name is required")
    if (value === JsonNull) return null
    val primitive = value as? JsonPrimitive
        ?: throw SerializationException("WorkerActionRequestDto.$name must be a string or null")
    if (!primitive.isString || primitive.contentOrNull == null) {
        throw SerializationException("WorkerActionRequestDto.$name must be a string or null")
    }
    return primitive.content
}
