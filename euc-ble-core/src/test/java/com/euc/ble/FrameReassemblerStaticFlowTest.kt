package com.euc.ble

import com.euc.ble.test.JUnit4AssertionsCompat
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class FrameReassemblerStaticFlowTest {
    @Test
    fun detectStaticMutableSharedFlowInFrameReassembler() {
        val target = "com.euc.ble.frames.FrameReassembler"
        val cls = try {
            Class.forName(target)
        } catch (e: ClassNotFoundException) {
            JUnit4AssertionsCompat.fail("Classe non trouvée: $target")
            return
        }

        val msfClass = try {
            Class.forName("kotlinx.coroutines.flow.MutableSharedFlow")
        } catch (e: ClassNotFoundException) {
            // pas de dépendance kotlinx.coroutines en test -> rien à inspecter
            return
        }

        val staticFields = cls.declaredFields.filter {
            Modifier.isStatic(it.modifiers) && msfClass.isAssignableFrom(it.type)
        }

        val companion = cls.declaredClasses.firstOrNull { it.simpleName == "Companion" }
        val companionStaticFields = companion?.declaredFields?.filter {
            Modifier.isStatic(it.modifiers) && msfClass.isAssignableFrom(it.type)
        } ?: emptyList()

        if (staticFields.isNotEmpty() || companionStaticFields.isNotEmpty()) {
            val names = (staticFields + companionStaticFields).joinToString(", ") { it.name }
            JUnit4AssertionsCompat.fail("MutableSharedFlow static trouvé(s) dans $target : $names")
        }
    }
}
