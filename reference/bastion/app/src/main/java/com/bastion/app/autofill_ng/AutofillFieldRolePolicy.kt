package com.bastion.app.autofill_ng

import com.bastion.app.autofill_ng.EnhancedAutofillStructureParserV2.Accuracy
import com.bastion.app.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint

internal data class AutofillFieldRoleCandidate<T>(
    val value: T,
    val hint: FieldHint,
    val score: Float,
    val strongestAccuracy: Accuracy,
)

internal data class AutofillFieldRoleSelection<T>(
    val value: T,
    val resolvedExplicitAccountPasswordConflict: Boolean,
    /**
     * 当 [resolvedExplicitAccountPasswordConflict] 为 true 时，被"账户覆盖密码"策略丢弃的
     * 最高分密码候选。调用方应将其作为独立 ParsedItem(PASSWORD) 保留，避免密码框被
     * 彻底移出可填目标（Via/系统 WebView 在 PayPal 等站点会把用户名/密码框分配相同
     * autofillId，落入同组后被该策略整体丢弃，导致密码框填不进）。
     * 对齐 Bitwarden：密码候选只要 hint=password 且非 forceAutofillOff 就保留为可填目标。
     */
    val droppedPasswordCandidate: T? = null,
)

internal object AutofillFieldRolePolicy {
    fun <T> select(candidates: List<AutofillFieldRoleCandidate<T>>): T? {
        return selectWithDiagnostics(candidates)?.value
    }

    fun <T> selectWithDiagnostics(
        candidates: List<AutofillFieldRoleCandidate<T>>
    ): AutofillFieldRoleSelection<T>? {
        if (candidates.isEmpty()) return null

        val hasPasswordCandidate = candidates.any { it.hint.isPasswordHint() }
        val explicitAccountCandidates = candidates.filter { candidate ->
            candidate.hint.isAccountHint() &&
                candidate.strongestAccuracy.score >= Accuracy.MEDIUM.score
        }
        val passwordCandidates = candidates.filter { it.hint.isPasswordHint() }
        val eligibleCandidates = if (
            hasPasswordCandidate && explicitAccountCandidates.isNotEmpty()
        ) {
            explicitAccountCandidates
        } else {
            candidates
        }

        val selected = eligibleCandidates
            .maxWithOrNull(
                compareBy<AutofillFieldRoleCandidate<T>> { it.score }
                    .thenBy { it.strongestAccuracy.score }
            )
            ?: return null
        val conflictResolved =
            hasPasswordCandidate && explicitAccountCandidates.isNotEmpty()
        // 冲突解决时，保留被丢弃的最高分密码候选，供调用方追加为独立 target。
        val droppedPassword = if (conflictResolved) {
            passwordCandidates.maxWithOrNull(
                compareBy<AutofillFieldRoleCandidate<T>> { it.score }
                    .thenBy { it.strongestAccuracy.score }
            )?.value
        } else {
            null
        }
        return AutofillFieldRoleSelection(
            value = selected.value,
            resolvedExplicitAccountPasswordConflict = conflictResolved,
            droppedPasswordCandidate = droppedPassword,
        )
    }

    private fun FieldHint.isAccountHint(): Boolean =
        this == FieldHint.USERNAME ||
            this == FieldHint.EMAIL_ADDRESS ||
            this == FieldHint.PHONE_NUMBER

    private fun FieldHint.isPasswordHint(): Boolean =
        this == FieldHint.PASSWORD || this == FieldHint.NEW_PASSWORD
}
