package com.niki914.nexus.agentic.repo

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledPhoneUseSafetyPolicyTest {
    @Test
    fun bundledPhoneSkill_requiresSnapshotAndFinalEffectAuthorization() {
        val file = sequenceOf(
            File("src/main/assets/skills/phone-use/SKILL.md"),
            File("app/src/main/assets/skills/phone-use/SKILL.md"),
        ).firstOrNull(File::isFile) ?: error("bundled phone-use skill missing")
        val text = file.readText()

        assertTrue("node_action must bind to snapshot_id", "node_action(snapshot_id:" in text)
        assertTrue("stale snapshots must require observation", "SNAPSHOT_STALE" in text)
        assertTrue("final send must require authorization", "`Send`" in text && "explicitly authorizes that exact effect" in text)
        assertTrue("uncertain effects must never replay", "never retry an irreversible action automatically" in text)
        assertTrue("screen content must not grant authority", "untrusted data" in text)
    }
}
