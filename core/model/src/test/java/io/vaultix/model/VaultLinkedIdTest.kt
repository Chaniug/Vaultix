package io.vaultix.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 关联字段编号（[VaultLinkedId]）编码锁定。
 *
 * 背景：Vaultix 曾把 linkedId 当成 1/2/3 的顺序编号（用户名=1、密码=2…），
 * 与 Bitwarden 官方的**分段编码**（100/300/400 段）完全不符，导致服务端下发的
 * Linked 字段匹配不到所指字段、退化成「未知关联字段」。这组测试就是防这个回归。
 */
class VaultLinkedIdTest {

    @Test
    fun loginSegmentUses100Range() {
        assertThat(VaultLinkedId.LoginUsername.code).isEqualTo(100)
        assertThat(VaultLinkedId.LoginPassword.code).isEqualTo(101)
    }

    @Test
    fun cardSegmentUses300Range() {
        assertThat(VaultLinkedId.CardCardholderName.code).isEqualTo(300)
        assertThat(VaultLinkedId.CardExpMonth.code).isEqualTo(301)
        assertThat(VaultLinkedId.CardExpYear.code).isEqualTo(302)
        assertThat(VaultLinkedId.CardCode.code).isEqualTo(303)
        assertThat(VaultLinkedId.CardBrand.code).isEqualTo(304)
        assertThat(VaultLinkedId.CardNumber.code).isEqualTo(305)
    }

    @Test
    fun identitySegmentUses400Range() {
        assertThat(VaultLinkedId.IdentityTitle.code).isEqualTo(400)
        assertThat(VaultLinkedId.IdentityMiddleName.code).isEqualTo(401)
        assertThat(VaultLinkedId.IdentityAddress1.code).isEqualTo(402)
        assertThat(VaultLinkedId.IdentityCity.code).isEqualTo(405)
        assertThat(VaultLinkedId.IdentitySsn.code).isEqualTo(412)
        assertThat(VaultLinkedId.IdentityUsername.code).isEqualTo(413)
        assertThat(VaultLinkedId.IdentityPassportNumber.code).isEqualTo(414)
        assertThat(VaultLinkedId.IdentityLastName.code).isEqualTo(417)
        assertThat(VaultLinkedId.IdentityFullName.code).isEqualTo(418)
    }

    @Test
    fun smallSequentialIdsAreNotLinkedFields() {
        // 关键回归点：官方不存在 1/2/3/4 这类编号
        assertThat(VaultLinkedId.fromCode(1)).isNull()
        assertThat(VaultLinkedId.fromCode(2)).isNull()
        assertThat(VaultLinkedId.fromCode(3)).isNull()
        assertThat(VaultLinkedId.fromCode(4)).isNull()
        assertThat(VaultLinkedId.fromCode(null)).isNull()
    }

    @Test
    fun fromCodeResolvesOfficialValues() {
        assertThat(VaultLinkedId.fromCode(100)).isEqualTo(VaultLinkedId.LoginUsername)
        assertThat(VaultLinkedId.fromCode(101)).isEqualTo(VaultLinkedId.LoginPassword)
        assertThat(VaultLinkedId.fromCode(300)).isEqualTo(VaultLinkedId.CardCardholderName)
        assertThat(VaultLinkedId.fromCode(400)).isEqualTo(VaultLinkedId.IdentityTitle)
        // 未知编号不抛异常（个别损坏条目不应让列表加载失败）
        assertThat(VaultLinkedId.fromCode(999)).isNull()
    }
}
