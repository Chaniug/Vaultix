package com.bastion.app.autofill_ng

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.bastion.app.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint
import com.bastion.app.data.model.BillingAddressData

class AutofillBillingAddressMappingTest {

    @Test
    fun billingAddressFieldsMapToAutofillHints() {
        val data = sampleAddress()

        assertEquals("Bastion User", mapBillingAddressAutofillValue(FieldHint.PERSON_NAME.name, data))
        assertEquals("Bastion", mapBillingAddressAutofillValue(FieldHint.COMPANY_NAME.name, data))
        assertEquals("1 Sakura Street, Apt 8, Tokyo, Tokyo, 100-0001, JP", mapBillingAddressAutofillValue(FieldHint.POSTAL_ADDRESS.name, data))
        assertEquals("100-0001", mapBillingAddressAutofillValue(FieldHint.POSTAL_CODE.name, data))
        assertEquals("Tokyo", mapBillingAddressAutofillValue(FieldHint.ADDRESS_CITY.name, data))
        assertEquals("JP", mapBillingAddressAutofillValue(FieldHint.ADDRESS_COUNTRY.name, data))
        assertEquals("bastion@example.com", mapBillingAddressAutofillValue(FieldHint.EMAIL_ADDRESS.name, data))
    }

    @Test
    fun billingAddressSearchCoversAddressAndContactFields() {
        val data = sampleAddress()

        assertTrue(data.matchesAutofillSearch("sakura"))
        assertTrue(data.matchesAutofillSearch("100-0001"))
        assertTrue(data.matchesAutofillSearch("bastion@example"))
    }

    private fun sampleAddress(): BillingAddressData =
        BillingAddressData(
            fullName = "Bastion User",
            company = "Bastion",
            streetAddress = "1 Sakura Street",
            apartment = "Apt 8",
            city = "Tokyo",
            stateProvince = "Tokyo",
            postalCode = "100-0001",
            country = "JP",
            phone = "+81 00 0000 0000",
            email = "bastion@example.com",
        )
}
