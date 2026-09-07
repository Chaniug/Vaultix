package com.bastion.app.utils

import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavBillingAddressBackupGuardTest {

    @Test
    fun webDavBackupAndRestoreKeepBillingAddressesInCardWalletBackup() {
        val source = projectFile("app/src/main/java/com/bastion/app/utils/WebDavHelper.kt")

        // 正则仅容忍空白与换行排版，语义锚点(ItemType.xxx / includeXxx / restoreXxx)保持原样
        assertTrue(source.contains(Regex("item\\.itemType\\s*!=\\s*ItemType\\.BILLING_ADDRESS")))
        assertTrue(source.contains(Regex("ItemType\\.BILLING_ADDRESS\\s*->\\s*preferences\\.includeBankCards\\s*\\|\\|\\s*preferences\\.includeDocuments")))
        assertTrue(source.contains(Regex("ItemType\\.BILLING_ADDRESS\\s*->\\s*File\\s*\\(\\s*foldersRootDir,\\s*\"\\\$folderKey/billing_addresses\"\\s*\\)")))
        assertTrue(source.contains(Regex("ItemType\\.BILLING_ADDRESS\\s*->\\s*\"billing_address\"")))
        assertTrue(source.contains(Regex("restoreCardWalletItemFromJson\\s*\\(\\s*tempFile,\\s*ItemType\\.BILLING_ADDRESS\\s*\\)")))
        assertTrue(source.contains(Regex("deleteAllLocalItemsByType\\s*\\(\\s*com\\.bastion\\.app\\.data\\.ItemType\\.BILLING_ADDRESS\\s*\\)")))
        assertTrue(source.contains(Regex("billingAddresses\\s*=\\s*cardWalletItems\\.count\\s*\\{\\s*it\\.itemType\\s*==\\s*ItemType\\.BILLING_ADDRESS\\s*\\}")))
        assertTrue(source.contains(Regex("billingAddresses\\s*=\\s*if\\s*\\(\\s*restoredBillingAddressCount\\s*>\\s*0\\s*\\)\\s*restoredBillingAddressCount\\s*else\\s*billingAddressItems")))
    }

    @Test
    fun webDavBackupAndRestoreKeepPaymentAccountsInCardWalletBackup() {
        val source = projectFile("app/src/main/java/com/bastion/app/utils/WebDavHelper.kt")

        assertTrue(source.contains(Regex("item\\.itemType\\s*!=\\s*ItemType\\.PAYMENT_ACCOUNT")))
        assertTrue(source.contains(Regex("ItemType\\.PAYMENT_ACCOUNT\\s*->\\s*preferences\\.includeBankCards\\s*\\|\\|\\s*preferences\\.includeDocuments")))
        assertTrue(source.contains(Regex("ItemType\\.PAYMENT_ACCOUNT\\s*->\\s*File\\s*\\(\\s*foldersRootDir,\\s*\"\\\$folderKey/payment_accounts\"\\s*\\)")))
        assertTrue(source.contains(Regex("ItemType\\.PAYMENT_ACCOUNT\\s*->\\s*\"payment_account\"")))
        assertTrue(source.contains(Regex("restoreCardWalletItemFromJson\\s*\\(\\s*tempFile,\\s*ItemType\\.PAYMENT_ACCOUNT\\s*\\)")))
        assertTrue(source.contains(Regex("deleteAllLocalItemsByType\\s*\\(\\s*com\\.bastion\\.app\\.data\\.ItemType\\.PAYMENT_ACCOUNT\\s*\\)")))
        assertTrue(source.contains(Regex("paymentAccounts\\s*=\\s*cardWalletItems\\.count\\s*\\{\\s*it\\.itemType\\s*==\\s*ItemType\\.PAYMENT_ACCOUNT\\s*\\}")))
        assertTrue(source.contains(Regex("paymentAccounts\\s*=\\s*if\\s*\\(\\s*restoredPaymentAccountCount\\s*>\\s*0\\s*\\)\\s*restoredPaymentAccountCount\\s*else\\s*paymentAccountItems")))
    }

    @Test
    fun backupReportsExposeBillingAddressCounts() {
        val source = projectFile("app/src/main/java/com/bastion/app/data/BackupReport.kt")

        assertTrue(source.contains(Regex("val\\s+billingAddresses\\s*:\\s*Int\\s*=\\s*0")))
        assertTrue(source.contains(Regex("val\\s+paymentAccounts\\s*:\\s*Int\\s*=\\s*0")))
        // 报告文案：敏感变量锚点 ${...} 保持原样，容忍全/半角冒号与空白
        assertTrue(source.contains(Regex("账单地址\\s*[:：]\\s*\\$\\{successItems\\.billingAddresses\\}/\\$\\{totalItems\\.billingAddresses\\}")))
        assertTrue(source.contains(Regex("账单地址\\s*[:：]\\s*\\$\\{restoredSuccessfully\\.billingAddresses\\}/\\$\\{backupContains\\.billingAddresses\\}")))
        assertTrue(source.contains(Regex("支付方式\\s*[:：]\\s*\\$\\{successItems\\.paymentAccounts\\}/\\$\\{totalItems\\.paymentAccounts\\}")))
        assertTrue(source.contains(Regex("支付方式\\s*[:：]\\s*\\$\\{restoredSuccessfully\\.paymentAccounts\\}/\\$\\{backupContains\\.paymentAccounts\\}")))
        assertTrue(source.contains(Regex("passwords\\s*\\+\\s*notes\\s*\\+\\s*totp\\s*\\+\\s*bankCards\\s*\\+\\s*documents\\s*\\+\\s*billingAddresses\\s*\\+\\s*paymentAccounts")))
    }

    private fun projectFile(relativePath: String): String {
        val start = Paths.get("").toAbsolutePath()
        var cursor = start
        while (cursor.parent != null) {
            val candidate = cursor.resolve(relativePath).toFile()
            if (candidate.exists()) {
                return candidate.readText()
            }
            cursor = cursor.parent
        }
        error("Project file not found from $start: $relativePath")
    }
}
