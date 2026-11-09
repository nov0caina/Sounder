package org.nov0caina.sounder.music

import org.junit.Assert.assertEquals
import org.junit.Test
import org.nov0caina.sounder.IntegerTable
import org.nov0caina.sounder.music.locations.LocationMode

class LocationModeTest {

    @Test
    fun testLocationModeMapping() {
        assertEquals(LocationMode.MEDIA_STORE, LocationMode.fromInt(IntegerTable.LOCATION_MODE_MEDIA_STORE))
        assertEquals(LocationMode.SAF, LocationMode.fromInt(IntegerTable.LOCATION_MODE_SAF))
        assertEquals(IntegerTable.LOCATION_MODE_MEDIA_STORE, LocationMode.MEDIA_STORE.intCode)
        assertEquals(IntegerTable.LOCATION_MODE_SAF, LocationMode.SAF.intCode)
    }
}

