/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.contrib.streaming.state;

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.core.memory.ByteArrayOutputStreamWithPos;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class RocksDBMergeIteratorTest {

	private static final int NUM_KEY_VAL_STATES = 50;
	private static final int MAX_NUM_KEYS = 20;

	@Rule
	public TemporaryFolder tempFolder = new TemporaryFolder();

	@Test
	public void testEmptyMergeIterator() throws IOException {
		RocksDBKeyedStateBackend.RocksDBMergeIterator emptyIterator =
				new RocksDBKeyedStateBackend.RocksDBMergeIterator(Collections.EMPTY_LIST, 2);
		Assert.assertFalse(emptyIterator.isValid());
	}

	@Test
	public void testMergeIteratorByte() throws Exception {
		Assert.assertTrue(MAX_NUM_KEYS <= Byte.MAX_VALUE);

		testMergeIterator(Byte.MAX_VALUE);
	}

	@Test
	public void testMergeIteratorShort() throws Exception {
		Assert.assertTrue(MAX_NUM_KEYS <= Byte.MAX_VALUE);

		testMergeIterator(Short.MAX_VALUE);
	}

	public void testMergeIterator(int maxParallelism) throws Exception {
		Random random = new Random(1234);

		RocksDB rocksDB = RocksDB.open(tempFolder.getRoot().getAbsolutePath());
		try {
			List<Tuple2<RocksIterator, Integer>> rocksIteratorsWithKVStateId = new ArrayList<>();
			List<Tuple2<ColumnFamilyHandle, Integer>> columnFamilyHandlesWithKeyCount = new ArrayList<>();

			int totalKeysExpected = 0;

			for (int c = 0; c < NUM_KEY_VAL_STATES; ++c) {
				ColumnFamilyHandle handle = rocksDB.createColumnFamily(
						new ColumnFamilyDescriptor(("column-" + c).getBytes(ConfigConstants.DEFAULT_CHARSET)));

				ByteArrayOutputStreamWithPos bos = new ByteArrayOutputStreamWithPos();
				DataOutputStream dos = new DataOutputStream(bos);

				int numKeys = random.nextInt(MAX_NUM_KEYS + 1);

				for (int i = 0; i < numKeys; ++i) {
					if (maxParallelism <= Byte.MAX_VALUE) {
						dos.writeByte(i);
					} else {
						dos.writeShort(i);
					}
					dos.writeInt(i);
					byte[] key = bos.toByteArray();
					byte[] val = new byte[]{42};
					rocksDB.put(handle, key, val);

					bos.reset();
				}
				columnFamilyHandlesWithKeyCount.add(new Tuple2<>(handle, numKeys));
				totalKeysExpected += numKeys;
			}

			int id = 0;
			for (Tuple2<ColumnFamilyHandle, Integer> columnFamilyHandle : columnFamilyHandlesWithKeyCount) {
				rocksIteratorsWithKVStateId.add(new Tuple2<>(rocksDB.newIterator(columnFamilyHandle.f0), id));
				++id;
			}

			RocksDBKeyedStateBackend.RocksDBMergeIterator mergeIterator = new RocksDBKeyedStateBackend.RocksDBMergeIterator(rocksIteratorsWithKVStateId, maxParallelism <= Byte.MAX_VALUE ? 1 : 2);

			int prevKVState = -1;
			int prevKey = -1;
			int prevKeyGroup = -1;
			int totalKeysActual = 0;

			while (mergeIterator.isValid()) {
				ByteBuffer bb = ByteBuffer.wrap(mergeIterator.key());

				int keyGroup = maxParallelism > Byte.MAX_VALUE ? bb.getShort() : bb.get();
				int key = bb.getInt();

				Assert.assertTrue(keyGroup >= prevKeyGroup);
				Assert.assertTrue(key >= prevKey);
				Assert.assertEquals(prevKeyGroup != keyGroup, mergeIterator.isNewKeyGroup());
				Assert.assertEquals(prevKVState != mergeIterator.kvStateId(), mergeIterator.isNewKeyValueState());

				prevKeyGroup = keyGroup;
				prevKVState = mergeIterator.kvStateId();

				//System.out.println(keyGroup + " " + key + " " + mergeIterator.kvStateId());
				mergeIterator.next();
				++totalKeysActual;
			}

			Assert.assertEquals(totalKeysExpected, totalKeysActual);

			for (Tuple2<ColumnFamilyHandle, Integer> handleWithCount : columnFamilyHandlesWithKeyCount) {
				rocksDB.dropColumnFamily(handleWithCount.f0);
			}
		} finally {
			rocksDB.close();
		}
	}

}
