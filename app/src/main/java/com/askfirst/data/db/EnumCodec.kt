package com.askfirst.data.db

import com.askfirst.domain.model.QuestionKind
import com.askfirst.domain.model.TurnRole

/**
 * 枚举与数据库字符串的编解码。
 * **遇到未知值直接抛**，不许静默 fallback——静默降级会让状态机行为无法解释（docs/03 §6）。
 */
object EnumCodec {
    fun turnRole(db: String): TurnRole = TurnRole.fromDb(db)
    fun turnRoleDb(role: TurnRole): String = role.db

    fun questionKind(db: String?): QuestionKind = QuestionKind.fromDb(db ?: "open")
    fun questionKindDb(kind: QuestionKind): String = kind.db
}