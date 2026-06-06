package app.ballbox.machineadssync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class MachineAdsSyncEngineTest {
    @Test
    fun resolvesNestedTargetUnderRoot() {
        val root = File("build/test-target").canonicalFile

        val result = MachineAdsSyncEngine.resolveOutputFile(
            root.path,
            "ImageScreen/asset.jpg"
        )

        assertEquals(File(root, "ImageScreen/asset.jpg"), result)
    }

    @Test
    fun rejectsParentTraversal() {
        val root = File("build/test-target").canonicalPath

        assertThrows(IllegalArgumentException::class.java) {
            MachineAdsSyncEngine.resolveOutputFile(root, "../outside.mp4")
        }
    }

    @Test
    fun rejectsAbsoluteTarget() {
        val root = File("build/test-target").canonicalPath

        assertThrows(IllegalArgumentException::class.java) {
            MachineAdsSyncEngine.resolveOutputFile(root, "/sdcard/outside.mp4")
        }
    }
}
