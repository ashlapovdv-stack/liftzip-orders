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
                append("Ссылка: ").append(line.productUrl.orEmpty()).append("\n")
                append("Наименование: ").append(line.productNameSnapshot).append("\n")
                append("Количество: ").append(line.quantity.stripTrailingZeros().toPlainString())
            }
        }.joinToString("\n\n")
        return OrderMailDraft(
            orderId = order.id,
            groupId = order.groupId,
            subject = order.counterparty,
            body = text,
            attachments = lines.flatMapIndexed { index, line ->
                line.images.indices.map { imageIndex ->
                    OrderMailAttachment(line.id, index + 1, imageIndex)
                }
            },
        )
    }
}
