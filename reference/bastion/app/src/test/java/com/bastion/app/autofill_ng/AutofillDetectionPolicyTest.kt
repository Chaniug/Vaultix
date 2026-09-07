package com.bastion.app.autofill_ng

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.bastion.app.autofill_ng.EnhancedAutofillStructureParserV2.Accuracy
import com.bastion.app.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint

class AutofillDetectionPolicyTest {

    @Test
    fun ordinaryNumericQuantityDoesNotBecomeAnAutomaticLoginTarget() {
        assertEquals(Accuracy.LOW, AutofillDetectionPolicy.genericNumberFallbackAccuracy())
        assertFalse(
            AutofillDetectionPolicy.shouldKeepTarget(
                hint = FieldHint.USERNAME,
                accuracy = Accuracy.LOW,
                hasPasswordTarget = false,
                manualRequest = false,
            )
        )
    }

    @Test
    fun numericAccountNextToPasswordAndExplicitAccountFieldsRemainSupported() {
        assertTrue(
            AutofillDetectionPolicy.shouldKeepTarget(
                hint = FieldHint.USERNAME,
                accuracy = Accuracy.LOW,
                hasPasswordTarget = true,
                manualRequest = false,
            )
        )
        assertTrue(
            AutofillDetectionPolicy.shouldKeepTarget(
                hint = FieldHint.USERNAME,
                accuracy = Accuracy.MEDIUM,
                hasPasswordTarget = false,
                manualRequest = false,
            )
        )
    }

    @Test
    fun manualRequestCanKeepAWeakFieldForPickerAndNonAutofillMarking() {
        assertTrue(
            AutofillDetectionPolicy.shouldKeepTarget(
                hint = FieldHint.USERNAME,
                accuracy = Accuracy.LOWEST,
                hasPasswordTarget = false,
                manualRequest = true,
            )
        )
    }

    @Test
    fun hiddenWeakAccountFieldsAreRejectedButExplicitPasswordsAreKept() {
        assertFalse(
            AutofillDetectionPolicy.shouldIncludeHiddenCredential(
                hint = FieldHint.USERNAME,
                accuracy = Accuracy.LOW,
            )
        )
        assertTrue(
            AutofillDetectionPolicy.shouldIncludeHiddenCredential(
                hint = FieldHint.PASSWORD,
                accuracy = Accuracy.HIGH,
            )
        )
    }

    @Test
    fun shortIdUsernameLabelRequiresARealTokenBoundary() {
        assertTrue(AutofillDetectionPolicy.matchesUsernameLabel("user id"))
        assertTrue(AutofillDetectionPolicy.matchesUsernameLabel("customer_id"))
        assertFalse(AutofillDetectionPolicy.matchesUsernameLabel("validity period"))
        assertFalse(AutofillDetectionPolicy.matchesUsernameLabel("paid amount"))
    }

    @Test
    fun explicitPhoneMetadataIsRecognizedWithoutPromotingGenericNumericFields() {
        assertTrue(AutofillDetectionPolicy.matchesPhoneFieldName("mobilePhone"))
        assertTrue(AutofillDetectionPolicy.matchesPhoneFieldName("请输入手机号码"))
        assertFalse(AutofillDetectionPolicy.matchesPhoneFieldName("quantity"))
        assertTrue(
            AutofillDetectionPolicy.shouldKeepTarget(
                hint = FieldHint.PHONE_NUMBER,
                accuracy = Accuracy.MEDIUM,
                hasPasswordTarget = false,
                manualRequest = false,
            )
        )
    }
}
