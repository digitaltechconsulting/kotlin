/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */
package org.jetbrains.kotlin.serialization.konan

import org.jetbrains.kotlin.backend.common.serialization.metadata.SourceFileMap
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.konan.library.KonanLibrary
import org.jetbrains.kotlin.konan.library.resolver.PackageAccessedHandler
import org.jetbrains.kotlin.metadata.deserialization.NameResolverImpl
import org.jetbrains.kotlin.library.metadata.KlibMetadataProtoBuf
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.serialization.deserialization.DeserializationComponents
import org.jetbrains.kotlin.serialization.deserialization.DeserializedPackageFragment
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedPackageMemberScope
import org.jetbrains.kotlin.serialization.deserialization.getClassId
import org.jetbrains.kotlin.serialization.deserialization.getName
import org.jetbrains.kotlin.storage.StorageManager


private val KonanLibrary.fileSources: SourceFileMap
    get() {
    val result = SourceFileMap()
    val proto = parseModuleHeader(moduleHeaderData)
    proto.fileList.forEachIndexed { index, it ->
        result.provide(it, index, this)
    }
    return result
}


class KonanPackageFragment(
        fqName: FqName,
        private val library: KonanLibrary,
        private val packageAccessedHandler: PackageAccessedHandler?,
        storageManager: StorageManager,
        module: ModuleDescriptor,
        partName: String
) : DeserializedPackageFragment(fqName, storageManager, module) {

    val sourceFileMap: SourceFileMap by lazy {
        library.fileSources
    }

    lateinit var components: DeserializationComponents

    override fun initialize(components: DeserializationComponents) {
        this.components = components
    }

    // The proto field is lazy so that we can load only needed
    // packages from the library.
    private val protoForNames: KlibMetadataProtoBuf.LinkDataPackageFragment by lazy {
        parsePackageFragment(library.packageMetadata(fqName.asString(), partName))
    }

    val proto: KlibMetadataProtoBuf.LinkDataPackageFragment
        get() = protoForNames.also { packageAccessedHandler?.markPackageAccessed(fqName.asString()) }

    private val nameResolver by lazy {
        NameResolverImpl(protoForNames.stringTable, protoForNames.nameTable)
    }

    override val classDataFinder by lazy {
        KonanClassDataFinder(proto, nameResolver)
    }

    private val _memberScope by lazy {
        /* TODO: we fake proto binary versioning for now. */
        DeserializedPackageMemberScope(
                this,
                proto.getPackage(),
                nameResolver,
                KonanMetadataVersion.INSTANCE,
                /* containerSource = */ null,
                components) { loadClassNames() }
    }

    override fun getMemberScope(): DeserializedPackageMemberScope = _memberScope

    private val classifierNames: Set<Name> by lazy {
        val result = mutableSetOf<Name>()
        result.addAll(loadClassNames())
        protoForNames.getPackage().typeAliasList.mapTo(result) { nameResolver.getName(it.name) }
        result
    }

    fun hasTopLevelClassifier(name: Name): Boolean = name in classifierNames

    private fun loadClassNames(): Collection<Name> {

        val classNameList = protoForNames.classes.classNameList

        val names = classNameList.mapNotNull {
            val classId = nameResolver.getClassId(it)
            val shortName = classId.shortClassName
            if (!classId.isNestedClass) shortName else null
        }

        return names
    }
}
