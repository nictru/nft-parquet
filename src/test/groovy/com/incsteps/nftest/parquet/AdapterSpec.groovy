/*
 * Copyright 2025, Incremental Steps Software Solution
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.incsteps.nftest.parquet

import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class AdapterSpec extends Specification {

    @TempDir
    Path directory

    Path parquetFile

    def setup() {
        parquetFile = Files.createFile(directory.resolve('empty.parquet'))
    }

    def 'global overloads wrap local files without reading metadata'() {
        expect:
        Methods.parquet(parquetFile) instanceof ParquetWrapper
        Methods.parquet(parquetFile.toString()) instanceof ParquetWrapper
    }

    def 'extension static methods preserve all entry points and accept any Map'() {
        expect:
        PathExtension.getParquet(parquetFile) instanceof ParquetWrapper
        PathExtension.parquet(parquetFile) instanceof ParquetWrapper
        PathExtension.parquet(parquetFile, [:]) instanceof ParquetWrapper
        PathExtension.parquet(parquetFile, new TreeMap()) instanceof ParquetWrapper
        PathExtension.parquet(parquetFile, Collections.emptyMap()) instanceof ParquetWrapper
    }

    def 'extension supports property and method syntax'() {
        expect:
        use(PathExtension) {
            assert parquetFile.parquet instanceof ParquetWrapper
            assert parquetFile.parquet() instanceof ParquetWrapper
            assert parquetFile.parquet([:]) instanceof ParquetWrapper
            true
        }
    }

    @Unroll
    def 'extension rejects unsupported options #options'() {
        when:
        PathExtension.parquet(parquetFile, options)

        then:
        thrown(IllegalArgumentException)

        where:
        options << [null, [unsupported: true]]
    }

    @Unroll
    def 'null paths fail with IllegalArgumentException for #adapter'() {
        when:
        adapter.call()

        then:
        thrown(IllegalArgumentException)

        where:
        adapter << [
            { Methods.parquet((String) null) },
            { Methods.parquet((Path) null) },
            { PathExtension.getParquet(null) },
            { PathExtension.parquet(null) },
            { PathExtension.parquet(null, [:]) }
        ]
    }

    @Unroll
    def 'global String helper treats URI-like input as a literal local path #path'() {
        expect:
        Methods.parquet(path).@parquetFile == Paths.get(path).toFile().absoluteFile

        where:
        path << ['s3://bucket/data.parquet', 'https://example.com/data.parquet',
                 'file:///data.parquet', 's3:data.parquet']
    }

    @Unroll
    def 'adapters accept nonexistent #kind paths with spaces and colons without opening files'() {
        given:
        Path path = absolute ? directory.resolve(filename) : Paths.get(filename)

        expect:
        Files.notExists(path)
        Methods.parquet(path.toString()).@parquetFile == path.toFile().absoluteFile
        Methods.parquet(path).@parquetFile == path.toFile().absoluteFile
        PathExtension.getParquet(path) instanceof ParquetWrapper
        PathExtension.parquet(path) instanceof ParquetWrapper
        PathExtension.parquet(path, [:]) instanceof ParquetWrapper
        Files.notExists(path)

        where:
        kind       | absolute | filename
        'relative' | false    | 'sample:gene.parquet'
        'relative' | false    | 'folder with spaces/sample:gene data.parquet'
        'absolute' | true     | 'sample:gene.parquet'
        'absolute' | true     | 'folder with spaces/sample:gene data.parquet'
    }

    def 'non-file Path schemes are rejected before conversion to File'() {
        given:
        Path remote = Mock()
        remote.toUri() >> URI.create('s3://bucket/data.parquet')

        when:
        Methods.parquet(remote)

        then:
        thrown(IllegalArgumentException)
        0 * remote.toFile()

        when:
        PathExtension.parquet(remote, [:])

        then:
        thrown(IllegalArgumentException)
        0 * remote.toFile()
    }

}
