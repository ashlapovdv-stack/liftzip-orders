package ru.liftzip.orders.domain.model

import java.util.UUID

/**
 * Snapshot of a letter. Editing the order does not mutate an existing draft.
 * Attachments reference their original line and image index, not a modified source image.
 */
data class OrderMailDraft(
    val orderId: UUID,
    val groupId: UUID,
    val subject: String,
    val recipient: String = "",
    val body: String,
    val attachments: List<OrderMailAttachment>,
)

data class OrderMailAttachment(
    val lineId: UUID,
    val positionNumber: Int,
    val imageIndex: Int,
)

object OrderMailDraftFactory {
    fun create(details: OrderDetails): OrderMailDraft {
        val order = details.order
        val lines = details.lines
        val text = lines.mapIndexed { index, line ->
            buildString {
                append(index + 1).append(".\n")
                append("Временная ссылка: ").append(line.temporaryProductUrl.orEmpty()).append("\n")
                append("Постоянная ссылка: ").append(line.productUrl.orEmpty()).append("\n")
                append("Наименование: ").append(line.productNameSnapshot).append("\n")
                append("Количество: ").append(line.quantity?.stripTrailingZeros()?.toPlainString() ?: "—")
                if (line.comment.isNotBlank()) append("\nКомментарий к позиции:\n").append(line.comment)
            }
        }.joinToString("\n\n")
        val bodyWithComment = buildString {
            append(text)
            if (order.comment.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("Комментарий к заказу:\n").append(order.comment)
            }
        }
        return OrderMailDraft(
            orderId = order.id,
            groupId = order.groupId,
            subject = order.counterparty,
            body = bodyWithComment,
            attachments = lines.flatMapIndexed { index, line ->
                line.images.indices.map { imageIndex ->
                    OrderMailAttachment(line.id, index + 1, imageIndex)
                }
            },
        )
    }

    /** Removes the duplicate footer written by older versions while retaining the rest of the draft. */
    fun repairLegacyBody(body: String): String {
        val duplicateMarker = "\n\nКомментарий к заказу:\n"
        val duplicateStart = body.lastIndexOf(duplicateMarker)
        if (duplicateStart < 0) return body
        val comment = body.substring(duplicateStart + duplicateMarker.length)
        if (comment.isBlank() || comment != comment.trim()) return body
        val original = body.substring(0, duplicateStart)
        val inlineMarker = "Комментарий к заказу: "
        val firstStart = original.lastIndexOf("\n\n$inlineMarker").let { index ->
            if (index >= 0) index + 2 else if (original.startsWith(inlineMarker)) 0 else return body
        }
        return if (original.substring(firstStart + inlineMarker.length).trim() == comment) original else body
    }

}
