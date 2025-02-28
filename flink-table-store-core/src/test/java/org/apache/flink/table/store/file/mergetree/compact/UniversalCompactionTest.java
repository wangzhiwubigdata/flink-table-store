/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.store.file.mergetree.compact;

import org.apache.flink.table.store.file.compact.CompactUnit;
import org.apache.flink.table.store.file.data.DataFileMeta;
import org.apache.flink.table.store.file.mergetree.LevelSortedRun;
import org.apache.flink.table.store.file.mergetree.SortedRun;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.apache.flink.table.store.file.mergetree.compact.UniversalCompaction.createUnit;
import static org.assertj.core.api.Assertions.assertThat;

/** Test for {@link UniversalCompaction}. */
public class UniversalCompactionTest {

    @Test
    public void testOutputLevel() {
        assertThat(createUnit(createLevels(0, 0, 1, 3, 4), 5, 1).outputLevel()).isEqualTo(0);
        assertThat(createUnit(createLevels(0, 0, 1, 3, 4), 5, 2).outputLevel()).isEqualTo(0);
        assertThat(createUnit(createLevels(0, 0, 1, 3, 4), 5, 3).outputLevel()).isEqualTo(2);
        assertThat(createUnit(createLevels(0, 0, 1, 3, 4), 5, 4).outputLevel()).isEqualTo(3);
        assertThat(createUnit(createLevels(0, 0, 1, 3, 4), 5, 5).outputLevel()).isEqualTo(5);
    }

    @Test
    public void testPick() {
        UniversalCompaction compaction = new UniversalCompaction(25, 1, 3);

        // by size amplification
        Optional<CompactUnit> pick = compaction.pick(3, level0(1, 2, 3, 3));
        assertThat(pick.isPresent()).isTrue();
        long[] results = pick.get().files().stream().mapToLong(DataFileMeta::fileSize).toArray();
        assertThat(results).isEqualTo(new long[] {1, 2, 3, 3});

        // by size ratio
        pick = compaction.pick(3, level0(1, 1, 1, 50));
        assertThat(pick.isPresent()).isTrue();
        results = pick.get().files().stream().mapToLong(DataFileMeta::fileSize).toArray();
        assertThat(results).isEqualTo(new long[] {1, 1, 1});

        // by file num
        pick = compaction.pick(3, level0(1, 2, 3, 50));
        assertThat(pick.isPresent()).isTrue();
        results = pick.get().files().stream().mapToLong(DataFileMeta::fileSize).toArray();
        // 3 should be in the candidate, by size ratio after picking by file num
        assertThat(results).isEqualTo(new long[] {1, 2, 3});
    }

    @Test
    public void testSizeAmplification() {
        UniversalCompaction compaction = new UniversalCompaction(25, 0, 1);
        long[] sizes = new long[] {1};
        sizes = appendAndPickForSizeAmp(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {2});
        sizes = appendAndPickForSizeAmp(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {3});
        sizes = appendAndPickForSizeAmp(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {4});
        sizes = appendAndPickForSizeAmp(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {1, 4});
        sizes = appendAndPickForSizeAmp(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {6});
        sizes = appendAndPickForSizeAmp(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {1, 6});
        sizes = appendAndPickForSizeAmp(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {8});
        sizes = appendAndPickForSizeAmp(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {1, 8});
        sizes = appendAndPickForSizeAmp(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {1, 1, 8});
        sizes = appendAndPickForSizeAmp(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {11});
        sizes = appendAndPickForSizeAmp(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {1, 11});
        sizes = appendAndPickForSizeAmp(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {1, 1, 11});
        sizes = appendAndPickForSizeAmp(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {14});
        sizes = appendAndPickForSizeAmp(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {1, 14});
        sizes = appendAndPickForSizeAmp(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {1, 1, 14});
        sizes = appendAndPickForSizeAmp(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {1, 1, 1, 14});
        sizes = appendAndPickForSizeAmp(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {18});
    }

    @Test
    public void testSizeRatio() {
        UniversalCompaction compaction = new UniversalCompaction(25, 1, 5);
        long[] sizes = new long[] {1, 1, 1, 1};
        sizes = appendAndPickForSizeRatio(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {5});
        sizes = appendAndPickForSizeRatio(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {1, 5});
        sizes = appendAndPickForSizeRatio(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {1, 1, 5});
        sizes = appendAndPickForSizeRatio(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {1, 1, 1, 5});
        sizes = appendAndPickForSizeRatio(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {4, 5});
        sizes = appendAndPickForSizeRatio(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {1, 4, 5});
        sizes = appendAndPickForSizeRatio(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {1, 1, 4, 5});
        sizes = appendAndPickForSizeRatio(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {3, 4, 5});
        sizes = appendAndPickForSizeRatio(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {1, 3, 4, 5});
        sizes = appendAndPickForSizeRatio(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {2, 3, 4, 5});
        sizes = appendAndPickForSizeRatio(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {1, 2, 3, 4, 5});
        sizes = appendAndPickForSizeRatio(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {16});
        sizes = appendAndPickForSizeRatio(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {1, 16});
        sizes = appendAndPickForSizeRatio(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {1, 1, 16});
        sizes = appendAndPickForSizeRatio(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {1, 1, 1, 16});
        sizes = appendAndPickForSizeRatio(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {4, 16});
        sizes = appendAndPickForSizeRatio(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {1, 4, 16});
        sizes = appendAndPickForSizeRatio(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {1, 1, 4, 16});
        sizes = appendAndPickForSizeRatio(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {3, 4, 16});
        sizes = appendAndPickForSizeRatio(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {1, 3, 4, 16});
        sizes = appendAndPickForSizeRatio(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {2, 3, 4, 16});
        sizes = appendAndPickForSizeRatio(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {1, 2, 3, 4, 16});
        sizes = appendAndPickForSizeRatio(compaction, sizes);
        assertThat(sizes).isEqualTo(new long[] {11, 16});
    }

    @Test
    public void testSizeRatioThreshold() {
        long[] sizes = new long[] {8, 9, 10};
        assertThat(pickForSizeRatio(new UniversalCompaction(25, 10, 2), sizes))
                .isEqualTo(new long[] {8, 9, 10});
        assertThat(pickForSizeRatio(new UniversalCompaction(25, 20, 2), sizes))
                .isEqualTo(new long[] {27});
    }

    private List<LevelSortedRun> createLevels(int... levels) {
        List<LevelSortedRun> runs = new ArrayList<>();
        for (int size : levels) {
            runs.add(new LevelSortedRun(size, SortedRun.fromSingle(file(1))));
        }
        return runs;
    }

    private long[] appendAndPickForSizeAmp(UniversalCompaction compaction, long... sizes) {
        sizes = addSize(sizes);
        CompactUnit unit = compaction.pickForSizeAmp(3, level0(sizes));
        if (unit != null) {
            return new long[] {
                unit.files().stream()
                        .mapToLong(DataFileMeta::fileSize)
                        .reduce(Long::sum)
                        .getAsLong()
            };
        }
        return sizes;
    }

    private long[] appendAndPickForSizeRatio(UniversalCompaction compaction, long... sizes) {
        return pickForSizeRatio(compaction, addSize(sizes));
    }

    private long[] pickForSizeRatio(UniversalCompaction compaction, long... sizes) {
        CompactUnit unit = compaction.pickForSizeRatio(3, level0(sizes));
        if (unit != null) {
            List<Long> compact =
                    unit.files().stream().map(DataFileMeta::fileSize).collect(Collectors.toList());
            List<Long> result = new ArrayList<>();
            for (long size : sizes) {
                result.add(size);
            }
            compact.forEach(result::remove);
            result.add(0, compact.stream().reduce(Long::sum).get());
            return result.stream().mapToLong(Long::longValue).toArray();
        }
        return sizes;
    }

    private long[] addSize(long... sizes) {
        long[] newSizes = new long[sizes.length + 1];
        newSizes[0] = 1;
        System.arraycopy(sizes, 0, newSizes, 1, sizes.length);
        return newSizes;
    }

    private List<LevelSortedRun> level0(long... sizes) {
        List<LevelSortedRun> runs = new ArrayList<>();
        for (Long size : sizes) {
            runs.add(new LevelSortedRun(0, SortedRun.fromSingle(file(size))));
        }
        return runs;
    }

    private DataFileMeta file(long size) {
        return new DataFileMeta("", size, 1, null, null, null, null, 0, 0, 0, 0);
    }
}
