/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.perf.util

import com.intellij.util.io.exists
import java.nio.file.Paths

fun String.lastPathSegment() =
    Paths.get(this).last().toString()

fun pwd() =
    Paths.get("").toAbsolutePath().toString()

fun exists(path: String, vararg paths: String) =
    Paths.get(path, *paths).toAbsolutePath().exists()