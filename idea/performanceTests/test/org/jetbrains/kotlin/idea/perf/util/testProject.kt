/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.perf.util

import org.jetbrains.kotlin.idea.testFramework.ProjectOpenAction

class ExternalProject private constructor(val path: String, val openWith: ProjectOpenAction) {
    companion object {
        val KOTLIN_GRADLE = ExternalProject("../perfTestProject", ProjectOpenAction.GRADLE_PROJECT)
        val KOTLIN_JPS = ExternalProject("../perfTestProject", ProjectOpenAction.EXISTING_IDEA_PROJECT)
    }
}
