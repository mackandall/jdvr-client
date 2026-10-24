package com.mackandall.jdvr.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerCandidatesTest {
    @Test
    fun bareAddressGetsSchemeAndUsualPorts() {
        assertEquals(
            listOf("http://192.168.1.200", "http://192.168.1.200:8096"),
            serverCandidates(" 192.168.1.200/ "),
        )
    }

    @Test
    fun givenPortIsKept() {
        assertEquals(listOf("http://192.168.1.200:8097"), serverCandidates("192.168.1.200:8097"))
        assertEquals(listOf("http://[fd00::12]:8096"), serverCandidates("[fd00::12]:8096"))
    }

    @Test
    fun schemeAndPathAreKept() {
        assertEquals(listOf("https://tv.example.com", "https://tv.example.com:8096"), serverCandidates("https://tv.example.com"))
        assertEquals(listOf("https://example.com/jellyfin"), serverCandidates("https://example.com/jellyfin/"))
    }

    @Test
    fun emptyIsNothing() {
        assertEquals(emptyList<String>(), serverCandidates("  "))
    }
}
