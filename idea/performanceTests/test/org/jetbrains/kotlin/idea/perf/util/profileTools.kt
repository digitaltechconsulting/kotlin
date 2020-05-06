/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.perf.util

import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.Tools
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.profile.codeInspection.ProjectInspectionProfileManager

class ProfileTools {
    companion object {
        internal fun enableSingleInspection(project: Project, inspectionName: String) {
            InspectionProfileImpl.INIT_INSPECTIONS = true
            val profile = InspectionProfileImpl("$inspectionName-only")
            profile.disableAllTools(project)
            profile.enableTool(inspectionName, project)

            replaceProfile(project, profile)
        }

        private fun replaceProfile(project: Project, profile: InspectionProfileImpl) {
            preloadProfileTools(profile, project)
            val manager = InspectionProjectProfileManager.getInstance(project) as ProjectInspectionProfileManager
            manager.addProfile(profile)
            val prev = manager.currentProfile
            manager.setCurrentProfile(profile)
            Disposer.register(project, {
                InspectionProfileImpl.INIT_INSPECTIONS = false
                manager.setCurrentProfile(prev)
                manager.deleteProfile(profile)
            })
        }

        private fun preloadProfileTools(
            profile: InspectionProfileImpl,
            project: Project
        ) {
            // instantiate all tools to avoid extension loading in inconvenient moment
            profile.getAllEnabledInspectionTools(project).forEach { state: Tools -> state.tool.getTool() }
        }
    }
}

fun Statistic.ProjectScope.enableSingleInspection(inspectionName: String) =
    ProfileTools.enableSingleInspection(this.project, inspectionName)