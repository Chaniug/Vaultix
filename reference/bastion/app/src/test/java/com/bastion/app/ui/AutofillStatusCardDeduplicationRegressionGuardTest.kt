package com.bastion.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归守卫：自动填充设置页顶部状态卡片不得与下方「系统设置」入口重复。
 *
 * 背景：状态卡片曾在底部固定挂一个「设置 Bastion 为系统自动填充服务」按钮，
 * 与下方 SectionCard 中的同名项标题、点击行为完全一致，同一屏出现两个相同入口；
 * 且在「已设置为默认服务」状态下该按钮语义自相矛盾。
 */
class AutofillStatusCardDeduplicationRegressionGuardTest {

    private val screen: String by lazy {
        projectFile(
            "app/src/main/java/com/bastion/app/ui/screens/AutofillSettingsV2Screen.kt"
        ).readText()
    }

    @Test
    fun systemServiceEntryAppearsOnlyOnceAsTheSectionItem() {
        val occurrences = Regex("""R\.string\.autofill_v2_set_system_service\b""")
            .findAll(screen)
            .count()

        assertEquals(
            "「设置 Bastion 为系统自动填充服务」入口只应保留在「系统设置」分组中一处",
            1,
            occurrences
        )
        assertTrue(
            "该入口应作为 AutofillSettingItem 保留在下方分组",
            screen.contains("title = stringResource(R.string.autofill_v2_set_system_service)")
        )
    }

    @Test
    fun statusCardCallToActionOnlyShowsWhenServiceIsNotEnabled() {
        assertTrue(
            "状态卡片的行动按钮必须包裹在 !statusEnabled 条件内",
            screen.contains("if (!statusEnabled) {")
        )
        assertTrue(
            "未启用时应使用「前往系统设置」作为行动号召",
            screen.contains("stringResource(R.string.autofill_status_go_to_settings)")
        )
    }

    @Test
    fun statusSummaryIsHiddenWhenItDuplicatesTheDescription() {
        // 完全正常时 getSummary() 恒为「自动填充服务运行正常」，
        // 与上一行「Bastion 已设置为默认自动填充服务」语义重复。
        assertTrue(
            "摘要应仅在非 isFullyOperational 时展示",
            screen.contains("serviceStatus?.takeIf { !it.isFullyOperational() }")
        )
    }

    @Test
    fun statusCardColorMatchesTheStricterOperationalCheck() {
        // 配色若只看 isSystemEnabled && isAppEnabled，而摘要用 isFullyOperational()，
        // 在小米/华为等必然产生兼容性提示的设备上会出现「绿色卡片 + 异常摘要」的矛盾。
        assertTrue(
            "需要区分「已启用但需注意」的中间态",
            screen.contains("statusNeedsAttention")
        )
        assertTrue(
            "中间态应使用 tertiaryContainer 而非 primaryContainer",
            screen.contains("statusNeedsAttention -> MaterialTheme.colorScheme.tertiaryContainer")
        )
    }

    @Test
    fun deadStatusCardComponentStaysDeleted() {
        assertFalse(
            "AutofillStatusCard.kt 是零引用死代码，已删除，不应再被恢复",
            projectFile(
                "app/src/main/java/com/bastion/app/ui/components/AutofillStatusCard.kt"
            ).exists()
        )
    }

    private fun projectFile(relativePath: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!
        }
        return File(directory, relativePath)
    }
}
