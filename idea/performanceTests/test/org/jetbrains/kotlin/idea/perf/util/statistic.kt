/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.perf.util

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzerSettings
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.ide.startup.impl.StartupManagerImpl
import com.intellij.idea.IdeaTestApplication
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.JavaAwareProjectJdkTableImpl
import com.intellij.openapi.startup.StartupManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.TestDataProvider
import com.intellij.testFramework.UsefulTestCase
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import com.intellij.util.ArrayUtilRt
import com.intellij.util.ThrowableRunnable
import com.intellij.util.indexing.UnindexedFilesUpdater
import org.jetbrains.kotlin.idea.framework.KotlinSdkType
import org.jetbrains.kotlin.idea.perf.Stats
import org.jetbrains.kotlin.idea.perf.TestApplicationManager
import org.jetbrains.kotlin.idea.perf.performanceTest
import org.jetbrains.kotlin.idea.test.invalidateLibraryCache
import org.jetbrains.kotlin.idea.testFramework.*
import org.jetbrains.kotlin.idea.util.getProjectJdkTableSafe
import java.io.File
import java.nio.file.Paths

class Statistic {
    companion object {

        fun suite(
            name: String,
            stats: StatsScope,
            block: (StatsScope) -> Unit
        ) {
            println("##teamcity[testSuiteStarted name='$name']")
            try {
                stats.stats.use {
                    block(stats)
                }
            } finally {
                println("##teamcity[testSuiteFinished name='$name']")
            }
        }

        private fun highlightFile(project: Project, psiFile: PsiFile): List<HighlightInfo> {
            val document = FileDocumentManager.getInstance().getDocument(psiFile.virtualFile)!!
            val editor = EditorFactory.getInstance().getEditors(document).first()
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            return CodeInsightTestFixtureImpl.instantiateAndRun(psiFile, editor, ArrayUtilRt.EMPTY_INT_ARRAY, true)
        }
    }

    class StatsScope(val config: StatsScopeConfig, val stats: Stats, val rootDisposable: Disposable) {
        fun app(f: ApplicationScope.() -> Unit) = ApplicationScope(rootDisposable, this).use(f)

        fun <T> measure(name: String, f: MeasurementScope<T>.() -> Unit): List<T?> =
            MeasurementScope<T>(name, stats, config).apply(f).run()

        fun <T> measure(name: String, f: MeasurementScope<T>.() -> Unit, after: (() -> Unit)?): List<T?> =
            MeasurementScope<T>(name, stats, config, after = after).apply(f).run()
    }

    class MeasurementScope<T>(
        val name: String,
        val stats: Stats,
        val config: StatsScopeConfig,
        var before: () -> Unit = {},
        var test: (() -> T?)? = null,
        var after: (() -> Unit)? = null
    ) {
        fun run(): List<T?> {
            val value = mutableListOf<T?>()
            performanceTest<Unit, T> {
                name(name)
                stats(stats)
                warmUpIterations(config.warmup)
                iterations(config.iterations)
                setUp {
                    before()
                }
                test {
                    value.add(test?.invoke() ?: throw IllegalStateException("test procedure isn't set"))
                }
                tearDown {
                    after?.invoke()
                }
                profilerEnabled(config.profile)
            }
            return value
        }
    }

    class ApplicationScope(val rootDisposable: Disposable, val stats: StatsScope) : AutoCloseable {
        val application = initApp(rootDisposable)
        val jdk: Sdk = initSdk(rootDisposable)

        fun project(externalProject: ExternalProject, refresh: Boolean = false, block: ProjectScope.() -> Unit) =
            ProjectScope(ProjectScopeConfig(externalProject, refresh), this).use(block)

        fun project(path: String, openWith: ProjectOpenAction = ProjectOpenAction.EXISTING_IDEA_PROJECT, block: ProjectScope.() -> Unit) =
            ProjectScope(ProjectScopeConfig(path, openWith), this).use(block)

        fun gradleProject(path: String, refresh: Boolean = false, block: ProjectScope.() -> Unit) =
            ProjectScope(ProjectScopeConfig(path, ProjectOpenAction.GRADLE_PROJECT, refresh), this).use(block)

        override fun close() {
            application?.setDataProvider(null)
        }

        companion object {
            private fun initApp(rootDisposable: Disposable): TestApplicationManager? {
                val application = TestApplicationManager.getInstance()
                GradleProcessOutputInterceptor.install(rootDisposable)
                return application
            }

            private fun initSdk(rootDisposable: Disposable): Sdk {
                return runWriteAction {
                    val jdkTableImpl = JavaAwareProjectJdkTableImpl.getInstanceEx()
                    val homePath = if (jdkTableImpl.internalJdk.homeDirectory!!.name == "jre") {
                        jdkTableImpl.internalJdk.homeDirectory!!.parent.path
                    } else {
                        jdkTableImpl.internalJdk.homePath!!
                    }

                    val javaSdk = JavaSdk.getInstance()
                    val jdk = javaSdk.createJdk("1.8", homePath)
                    val internal = javaSdk.createJdk("IDEA jdk", homePath)

                    val jdkTable = getProjectJdkTableSafe()
                    jdkTable.addJdk(jdk, rootDisposable)
                    jdkTable.addJdk(internal, rootDisposable)
                    KotlinSdkType.setUpIfNeeded()
                    jdk
                }
            }
        }
    }


    class StatsScopeConfig(var warmup: Int = 2, var iterations: Int = 5, val profile: Boolean = false)

    class ProjectScopeConfig(val path: String, val openWith: ProjectOpenAction, val refresh: Boolean = false) {
        val name: String = path.lastPathSegment()

        constructor(externalProject: ExternalProject, refresh: Boolean) : this(externalProject.path, externalProject.openWith, refresh)
    }

    class ProjectScope(val config: ProjectScopeConfig, val app: ApplicationScope) : AutoCloseable {
        val project: Project = initProject(config, app)

        fun highlight(editorFile: EditorFile?) =
            editorFile?.let {
                highlightFile(project, editorFile.psiFile)
            } ?: throw IllegalStateException("editor isn't ready for highlight")


        fun editor(path: String) =
            Fixture.openFileInEditor(project, path)

        fun close(editorFile: EditorFile?) {
            commitAllDocuments()
            editorFile?.psiFile?.virtualFile?.let {
                FileEditorManager.getInstance(project).closeFile(it)
            }
        }

        fun <T> measure(vararg name: String, f: MeasurementScope<T>.() -> Unit): List<T?> {
            val after = { PsiManager.getInstance(project).dropPsiCaches() }
            return app.stats.measure("${app.stats.stats.name}-${name.joinToString("-")}", f, after)
        }

        override fun close() {
            RunAll(
                ThrowableRunnable {
                    if (project != null) {
                        DaemonCodeAnalyzerSettings.getInstance().isImportHintEnabled = true // return default value to avoid unnecessary save
                        (StartupManager.getInstance(project) as StartupManagerImpl).checkCleared()
                        (DaemonCodeAnalyzer.getInstance(project) as DaemonCodeAnalyzerImpl).cleanupAfterTest()
                        closeProject(project)
                    }
                }).run()
        }

        companion object {
            fun initProject(config: ProjectScopeConfig, app: ApplicationScope): Project {
                val projectPath = File(config.path).canonicalPath

                UsefulTestCase.assertTrue("path ${config.path} does not exist, check README.md", File(projectPath).exists())

                val openProject = OpenProject(
                    projectPath = config.path,
                    projectName = config.name,
                    jdk = app.jdk,
                    projectOpenAction = config.openWith
                )
                val project = ProjectOpenAction.openProject(openProject)
                openProject.projectOpenAction.postOpenProject(project, openProject)

                // indexing
                if (config.refresh) {
                    invalidateLibraryCache(project)
                }

                CodeInsightTestFixtureImpl.ensureIndexesUpToDate(project)

                dispatchAllInvocationEvents()
                with(DumbService.getInstance(project)) {
                    queueTask(UnindexedFilesUpdater(project))
                    completeJustSubmittedTasks()
                }
                dispatchAllInvocationEvents()

                Fixture.enableAnnotatorsAndLoadDefinitions(project)

                app.application?.setDataProvider(TestDataProvider(project))

                return project
            }
        }
    }
}

fun UsefulTestCase.statisticSuite(
    config: Statistic.StatsScopeConfig = Statistic.StatsScopeConfig(),
    block: Statistic.StatsScope.() -> Unit
) {
    Statistic.suite(this.javaClass.name, Statistic.StatsScope(config, Stats(name), testRootDisposable), block)
}