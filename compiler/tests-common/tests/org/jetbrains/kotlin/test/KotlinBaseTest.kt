/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test

import org.jetbrains.kotlin.test.testFramework.KtUsefulTestCase
import java.io.File
import java.util.*

abstract class KotlinBaseTest<F : KotlinBaseTest.TestFile> : KtUsefulTestCase() {

    @JvmField
    protected var coroutinesPackage: String? = null

    @Throws(Exception::class)
    override fun setUp() {
        super.setUp()
        coroutinesPackage = ""
    }

    @Throws(java.lang.Exception::class)
    protected open fun doTestWithCoroutinesPackageReplacement(filePath: String, coroutinesPackage: String) {
        this.coroutinesPackage = coroutinesPackage
        doTest(filePath)
    }

    @Throws(java.lang.Exception::class)
    protected open fun doTest(filePath: String) {
        val file = File(filePath)
        var expectedText = KotlinTestUtils.doLoadFile(file)
        if (!coroutinesPackage!!.isEmpty()) {
            expectedText = expectedText.replace("COROUTINES_PACKAGE", coroutinesPackage!!)
        }
        doMultiFileTest(file, createTestFilesFromFile(file, expectedText))
    }

    protected abstract fun createTestFilesFromFile(file: File, expectedText: String?): List<F>

    @Throws(java.lang.Exception::class)
    protected open fun doMultiFileTest(
        wholeFile: File,
        files: List<F>
    ) {
        throw UnsupportedOperationException("Multi-file test cases are not supported in this test")
    }

    open class TestFile(@JvmField val name: String, @JvmField val content: String) : Comparable<TestFile> {
        override operator fun compareTo(other: TestFile): Int {
            return name.compareTo(other.name)
        }

        override fun hashCode(): Int {
            return name.hashCode()
        }

        override fun equals(other: Any?): Boolean {
            return other is TestFile && other.name == name
        }

        override fun toString(): String {
            return name
        }
    }

    open class TestModule(
        @JvmField val name: String,
        @JvmField val dependenciesSymbols: List<String>,
        @JvmField val friendsSymbols: List<String>
    ) : Comparable<TestModule> {

        private val dependencies = ArrayList<TestModule>()
        private val friends = ArrayList<TestModule>()

        fun getDependencies(): MutableList<TestModule> = dependencies

        fun getFriends(): MutableList<TestModule> = friends

        override fun compareTo(other: TestModule): Int = name.compareTo(other.name)

        override fun toString(): String = name
    }


    open class ModuleAndDependencies<M> protected constructor(
        @JvmField val module: M,
        @JvmField val dependencies: List<String>,
        @JvmField val friends: List<String>
    )
}