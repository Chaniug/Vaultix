package com.bastion.app.viewmodel

import com.bastion.app.data.PredefinedSecurityQuestions
import com.bastion.app.repository.PasswordRepository
import com.bastion.app.security.SecurityManager
import java.util.Date
import kotlinx.coroutines.flow.first

/**
 * `PasswordViewModel` 主密码簇的抽取（B.3 集群 7）。
 *
 * ## 为什么需要这个类
 *
 * 集群 7（主密码 / 历史）是 B.3 剩余簇之一。行为测试网
 * `PasswordMasterAndHistoryBehaviorTest` 锁定语义后，本类承接主密码变更
 * （`changePassword`）与密保问题保存（`saveSecurityQuestions`），
 * `PasswordViewModel` 只保留薄委托。
 *
 * ## 注入策略（与集群 5c `PasswordMoveExecutor` / 集群 6 `PasswordArchiveOrchestrator` 一致）
 *
 * - **实例注入**：`repository` / `securityManager`。
 * - **函数引用注入**：`decryptForDisplay` —— VM 内 10+ 处复用，实现留在 VM。
 *
 * ## 与 VM 的边界
 *
 * `changePassword` 返回 `Boolean`（当前密码验证是否通过），VM 薄委托根据返回值
 * 更新 `_isAuthenticated` —— 语义与搬迁前（验证失败提前 return，不设置认证态）等价。
 */
class MasterPasswordOps(
    private val repository: PasswordRepository,
    private val securityManager: SecurityManager,
    private val decryptForDisplay: (String) -> String
) {

    /**
     * 变更主密码：验证当前密码 → 用当前密钥解密全部条目 → 设置新主密码 →
     * 用新密钥重加密全部条目。
     *
     * @return 当前密码验证通过并完成变更返回 true；验证失败返回 false（不触碰任何条目）。
     */
    suspend fun changePassword(currentPassword: String, newPassword: String): Boolean {
        // 1. 验证当前密码
        if (!securityManager.verifyMasterPassword(currentPassword)) {
            return false
        }

        // 2. 获取所有加密数据
        val allPasswords = repository.getAllPasswordEntries().first()

        // 3. 使用当前密码解密所有数据
        val decryptedPasswords = allPasswords.map { entry ->
            entry.copy(password = decryptForDisplay(entry.password))
        }

        // 4. 设置新密码
        securityManager.setMasterPassword(newPassword)

        // 5. 使用新密码重新加密所有数据
        decryptedPasswords.forEach { entry ->
            repository.updatePasswordEntry(entry.copy(
                password = securityManager.encryptData(entry.password),
                updatedAt = Date()
            ))
        }

        return true
    }

    /**
     * 保存密保问题（B.3 集群 7 TODO 补全）。
     *
     * 原实现是 TODO 桩（只加密答案、不落库、无调用方）。补全为：把
     * `(questionText, answer)` 列表按序映射为问题 1 / 问题 2，通过
     * [PredefinedSecurityQuestions] 解析问题 id（文本不匹配任何预置问题时视为
     * 自定义问题），最终落到 `securityManager.setSecurityQuestions`（该存储设施
     * 已存在并被 `SecurityQuestionsSetupScreen` 使用）。
     */
    suspend fun saveSecurityQuestions(questions: List<Pair<String, String>>) {
        if (questions.size < 2) return

        val (questionText1, answer1) = questions[0]
        val (questionText2, answer2) = questions[1]
        val question1Id = resolveQuestionId(questionText1)
        val question2Id = resolveQuestionId(questionText2)

        securityManager.setSecurityQuestions(
            question1Id = question1Id,
            answer1 = answer1.lowercase(),
            question2Id = question2Id,
            answer2 = answer2.lowercase(),
            question1Text = questionText1.takeIf { question1Id == PredefinedSecurityQuestions.CUSTOM_QUESTION_ID },
            question2Text = questionText2.takeIf { question2Id == PredefinedSecurityQuestions.CUSTOM_QUESTION_ID }
        )
    }

    private fun resolveQuestionId(questionText: String): Int {
        val match = PredefinedSecurityQuestions.questions.firstOrNull { it.questionText == questionText }
        return match?.id ?: PredefinedSecurityQuestions.CUSTOM_QUESTION_ID
    }
}
