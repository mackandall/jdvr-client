package com.mackandall.jdvr.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InsecureRemoteTest {
    @Test
    fun homeAddressesAreFine() {
        listOf(
            "http://192.168.1.188:8096",
            "http://10.0.0.5:8096/",
            "http://172.20.1.2:8096",
            "http://127.0.0.1:8096",
            "http://100.101.102.103:8096", // Tailscale
            "http://jellyfin:8096",
            "http://nas.local:8096",
            "http://media.lan:8096",
            "http://[fd00::12]:8096",
            "https://jellyfin.example.com",
            "https://203.0.113.9:8920",
        ).forEach { assertFalse(it, insecureRemote(it)) }
    }

    @Test
    fun plainHttpOverTheInternetIsFlagged() {
        listOf(
            "http://jellyfin.example.com",
            "http://203.0.113.9:8096",
            "http://172.32.0.1:8096",
            "http://[2001:db8::1]:8096",
        ).forEach { assertTrue(it, insecureRemote(it)) }
    }
}
