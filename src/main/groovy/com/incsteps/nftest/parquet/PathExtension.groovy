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

import groovy.transform.CompileStatic

import java.nio.file.Path

/**
 *
 * @author Jorge Aguilera <jorge@incsteps.com>
 */
@CompileStatic
class PathExtension {

    static ParquetWrapper getParquet(Path self) throws Exception {
        return parquet(self, [:])
    }

    static ParquetWrapper parquet(Path self) throws Exception {
        return parquet(self, [:])
    }

    static ParquetWrapper parquet(Path self, Map options) throws Exception {
        if (self == null) {
            throw new IllegalArgumentException('Parquet path must not be null')
        }
        if (!'file'.equalsIgnoreCase(self.toUri().getScheme())) {
            throw new IllegalArgumentException('Parquet path must use the local file scheme')
        }
        return new ParquetWrapper(self.toFile(), options)
    }


}
