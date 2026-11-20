/*
 *
 *  * Copyright (C) ${YEAR} Gauthier Le Bartz Lyan (Tritbool)
 *  * euc_ble_library — version 1.1.0 — licence GPL-3.0
 *  * https://github.com/Tritbool/euc_ble_library
 *
 */

package io.github.tritbool.euc.ble

import org.junit.jupiter.api.Tag

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Tag("slow")
annotation class SlowTest