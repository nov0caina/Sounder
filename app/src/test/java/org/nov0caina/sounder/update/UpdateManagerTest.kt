/*
 * Copyright (c) 2026 Sounder Project
 * UpdateManagerTest.kt is part of Sounder.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.nov0caina.sounder.update

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun testCheckIntervalConstantIsNineHours() {
        val expectedNineHoursMs = TimeUnit.HOURS.toMillis(9)
        assertEquals(32400000L, expectedNineHoursMs)
        assertEquals(expectedNineHoursMs, UpdateManager.CHECK_INTERVAL_MS)
    }

    @Test
    fun testCheckIntervalElapsedWhenNeverChecked() {
        val now = 100000000L
        val lastCheck = 0L
        assertTrue(UpdateManager.isCheckIntervalElapsed(lastCheck = lastCheck, now = now))
    }

    @Test
    fun testCheckIntervalElapsedWhenExactNineHours() {
        val lastCheck = 100000000L
        val now = lastCheck + TimeUnit.HOURS.toMillis(9)
        assertTrue(UpdateManager.isCheckIntervalElapsed(lastCheck = lastCheck, now = now))
    }

    @Test
    fun testCheckIntervalElapsedWhenMoreThanNineHours() {
        val lastCheck = 100000000L
        val now = lastCheck + TimeUnit.HOURS.toMillis(10)
        assertTrue(UpdateManager.isCheckIntervalElapsed(lastCheck = lastCheck, now = now))
    }

    @Test
    fun testCheckIntervalSkippedWhenUnderNineHours() {
        val lastCheck = 100000000L
        val now = lastCheck + TimeUnit.HOURS.toMillis(8)
        assertFalse(UpdateManager.isCheckIntervalElapsed(lastCheck = lastCheck, now = now))
    }

    @Test
    fun testCheckIntervalSkippedWhenJustUnderNineHours() {
        val lastCheck = 100000000L
        val now = lastCheck + TimeUnit.HOURS.toMillis(9) - 1L
        assertFalse(UpdateManager.isCheckIntervalElapsed(lastCheck = lastCheck, now = now))
    }

    @Test
    fun testSemVerParsingAndComparison() {
        val v100 = SemVer.parse("v1.0.0")
        val v101 = SemVer.parse("v1.0.1-sounder")
        val v110 = SemVer.parse("1.1.0")

        assertNotNull(v100)
        assertNotNull(v101)
        assertNotNull(v110)

        assertEquals(1, v100!!.major)
        assertEquals(0, v100.minor)
        assertEquals(0, v100.patch)

        assertEquals(1, v101!!.major)
        assertEquals(0, v101.minor)
        assertEquals(1, v101.patch)

        assertTrue(v101 > v100)
        assertTrue(v110!! > v101)
        assertFalse(v100 > v101)
    }
}
