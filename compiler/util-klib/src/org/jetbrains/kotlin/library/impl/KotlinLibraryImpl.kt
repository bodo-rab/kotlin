/*
 * Copyright 2010-2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.library.impl

import org.jetbrains.kotlin.konan.file.File
import org.jetbrains.kotlin.library.*
import org.jetbrains.kotlin.konan.properties.Properties
import org.jetbrains.kotlin.konan.properties.loadProperties

open class BaseKotlinLibraryImpl(
    val access: BaseLibraryAccess<KotlinLibraryLayout>,
    override val isDefault: Boolean
) : BaseKotlinLibrary {
    override val libraryFile get() = access.klib
    override val libraryName: String by lazy { access.inPlace { it.libraryName } }

    private val componentListAndHasPre14Manifest by lazy {
        access.inPlace { layout ->
            val listFiles = layout.libDir.listFiles
            listFiles
                .filter { it.isDirectory }
                .filter { it.listFiles.map { it.name }.contains(KLIB_MANIFEST_FILE_NAME) }
                .map { it.name } to listFiles.any { it.absolutePath == layout.pre_1_4_manifest.absolutePath }
        }
    }

    override val componentList: List<String> get() = componentListAndHasPre14Manifest.first

    override fun toString() = "$libraryName[default=$isDefault]"

    override val has_pre_1_4_manifest: Boolean get() = componentListAndHasPre14Manifest.second

    override val manifestProperties: Properties by lazy {
        access.inPlace { it.manifestFile.loadProperties() }
    }

    override val versions: KotlinLibraryVersioning by lazy {
        manifestProperties.readKonanLibraryVersioning()
    }
}

open class MetadataLibraryImpl(
    val access: MetadataLibraryAccess<MetadataKotlinLibraryLayout>
) : MetadataLibrary {

    override val moduleHeaderData: ByteArray by lazy {
        access.inPlace {
            it.moduleHeaderFile.readBytes()
        }
    }

    override fun packageMetadata(fqName: String, partName: String): ByteArray =
        access.inPlace {
            it.packageFragmentFile(fqName, partName).readBytes()
        }

    override fun packageMetadataParts(fqName: String): Set<String> =
        access.inPlace { inPlaceaccess ->
            val fileList =
                inPlaceaccess.packageFragmentsDir(fqName)
                    .listFiles
                    .mapNotNull {
                        it.name
                            .substringBeforeLast(KLIB_METADATA_FILE_EXTENSION_WITH_DOT, missingDelimiterValue = "")
                            .takeIf { it.isNotEmpty() }
                    }

            fileList.toSortedSet().also {
                require(it.size == fileList.size) { "Duplicated names: ${fileList.groupingBy { it }.eachCount().filter { (_, count) -> count > 1 }}" }
            }
        }
}

abstract class IrLibraryImpl(
    val access: IrLibraryAccess<IrKotlinLibraryLayout>
) : IrLibrary {
    override val dataFlowGraph by lazy {
        access.inPlace { it: IrKotlinLibraryLayout ->
            it.dataFlowGraphFile.let { if (it.exists) it.readBytes() else null }
        }
    }
}

class IrMonoliticLibraryImpl(_access: IrLibraryAccess<IrKotlinLibraryLayout>) : IrLibraryImpl(_access) {
    override fun fileCount(): Int = files.entryCount()

    override fun irDeclaration(index: Int, fileIndex: Int) = loadIrDeclaration(index, fileIndex)

    override fun type(index: Int, fileIndex: Int) = types.tableItemBytes(fileIndex, index)

    override fun signature(index: Int, fileIndex: Int) = signatures.tableItemBytes(fileIndex, index)

    override fun string(index: Int, fileIndex: Int) = strings.tableItemBytes(fileIndex, index)

    override fun body(index: Int, fileIndex: Int) = bodies.tableItemBytes(fileIndex, index)

    override fun file(index: Int) = files.tableItemBytes(index)

    private fun loadIrDeclaration(index: Int, fileIndex: Int) =
        combinedDeclarations.tableItemBytes(fileIndex, DeclarationId(index))

    private val combinedDeclarations: DeclarationIrMultiTableFileReader by lazy {
        DeclarationIrMultiTableFileReader(access.realFiles {
            it.irDeclarations
        })
    }

    private val types: IrMultiArrayFileReader by lazy {
        IrMultiArrayFileReader(access.realFiles {
            it.irTypes
        })
    }

    private val signatures: IrMultiArrayFileReader by lazy {
        IrMultiArrayFileReader(access.realFiles {
            it.irSignatures
        })
    }

    private val strings: IrMultiArrayFileReader by lazy {
        IrMultiArrayFileReader(access.realFiles {
            it.irStrings
        })
    }

    private val bodies: IrMultiArrayFileReader by lazy {
        IrMultiArrayFileReader(access.realFiles {
            it.irBodies
        })
    }

    private val files: IrArrayFileReader by lazy {
        IrArrayFileReader(access.realFiles {
            it.irFiles
        })
    }
}

class IrPerFileLibraryImpl(_access: IrLibraryAccess<IrKotlinLibraryLayout>) : IrLibraryImpl(_access) {

    private val directories by lazy {
        access.realFiles {
            it.irDir.listFiles.filter { f -> f.isDirectory && f.name.endsWith(".file") }
        }
    }

    private val fileToDeclarationMap = mutableMapOf<Int, DeclarationIrTableFileReader>()
    override fun irDeclaration(index: Int, fileIndex: Int): ByteArray {
        val dataReader = fileToDeclarationMap.getOrPut(fileIndex) {
            val fileDirectory = directories[fileIndex]
            DeclarationIrTableFileReader(access.realFiles {
                it.irDeclarations(fileDirectory)
            })
        }
        return dataReader.tableItemBytes(DeclarationId(index))
    }

    private val fileToTypeMap = mutableMapOf<Int, IrArrayFileReader>()
    override fun type(index: Int, fileIndex: Int): ByteArray {
        val dataReader = fileToTypeMap.getOrPut(fileIndex) {
            val fileDirectory = directories[fileIndex]
            IrArrayFileReader(access.realFiles {
                it.irTypes(fileDirectory)
            })
        }
        return dataReader.tableItemBytes(index)
    }

    override fun signature(index: Int, fileIndex: Int): ByteArray {
        val dataReader = fileToTypeMap.getOrPut(fileIndex) {
            val fileDirectory = directories[fileIndex]
            IrArrayFileReader(access.realFiles {
                it.irSignatures(fileDirectory)
            })
        }
        return dataReader.tableItemBytes(index)
    }

    private val fileToStringMap = mutableMapOf<Int, IrArrayFileReader>()
    override fun string(index: Int, fileIndex: Int): ByteArray {
        val dataReader = fileToStringMap.getOrPut(fileIndex) {
            val fileDirectory = directories[fileIndex]
            IrArrayFileReader(access.realFiles {
                it.irStrings(fileDirectory)
            })
        }
        return dataReader.tableItemBytes(index)
    }

    private val fileToBodyMap = mutableMapOf<Int, IrArrayFileReader>()
    override fun body(index: Int, fileIndex: Int): ByteArray {
        val dataReader = fileToBodyMap.getOrPut(fileIndex) {
            val fileDirectory = directories[fileIndex]
            IrArrayFileReader(access.realFiles {
                it.irBodies(fileDirectory)
            })
        }
        return dataReader.tableItemBytes(index)
    }

    override fun file(index: Int): ByteArray {
        return access.realFiles {
            it.irFile(directories[index]).readBytes()
        }
    }

    override fun fileCount(): Int {
        return directories.size
    }
}

open class KotlinLibraryImpl(
    val base: BaseKotlinLibraryImpl,
    val metadata: MetadataLibraryImpl,
    val ir: IrLibraryImpl
) : KotlinLibrary,
    BaseKotlinLibrary by base,
    MetadataLibrary by metadata,
    IrLibrary by ir

fun createKotlinLibrary(
    libraryFile: File,
    component: String,
    isDefault: Boolean = false,
    perFile: Boolean = false
): KotlinLibrary {
    val baseAccess = BaseLibraryAccess<KotlinLibraryLayout>(libraryFile, component)
    val metadataAccess = MetadataLibraryAccess<MetadataKotlinLibraryLayout>(libraryFile, component)
    val irAccess = IrLibraryAccess<IrKotlinLibraryLayout>(libraryFile, component)

    val base = BaseKotlinLibraryImpl(baseAccess, isDefault)
    val metadata = MetadataLibraryImpl(metadataAccess)
    val ir = if (perFile) IrPerFileLibraryImpl(irAccess) else IrMonoliticLibraryImpl(irAccess)

    return KotlinLibraryImpl(base, metadata, ir)
}

fun createKotlinLibraryComponents(
    libraryFile: File,
    isDefault: Boolean = true
) : List<KotlinLibrary> {
    val baseAccess = BaseLibraryAccess<KotlinLibraryLayout>(libraryFile, null)
    val base = BaseKotlinLibraryImpl(baseAccess, isDefault)
    return base.componentList.map {
        createKotlinLibrary(libraryFile, it, isDefault)
    }
}

@Deprecated("Use resolveSingleFileKlib() instead", replaceWith = ReplaceWith("resolveSingleFileKlib()"))
fun createKotlinLibrary(libraryFile: File): KotlinLibrary = resolveSingleFileKlib(libraryFile)

fun isKotlinLibrary(libraryFile: File): Boolean = try {
    resolveSingleFileKlib(libraryFile)
    true
} catch (e: Throwable) {
    false
}

fun isKotlinLibrary(libraryFile: java.io.File): Boolean =
    isKotlinLibrary(File(libraryFile.absolutePath))

val File.isPre_1_4_Library: Boolean
    get() {
        val baseAccess = BaseLibraryAccess<KotlinLibraryLayout>(this, null)
        val base = BaseKotlinLibraryImpl(baseAccess, false)
        return base.has_pre_1_4_manifest
    }