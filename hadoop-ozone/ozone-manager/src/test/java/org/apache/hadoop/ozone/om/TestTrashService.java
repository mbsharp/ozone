/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.hadoop.ozone.om;


import org.apache.commons.lang.RandomStringUtils;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.scm.container.common.helpers.ExcludeList;
import org.apache.hadoop.hdds.server.ServerUtils;
import org.apache.hadoop.hdds.utils.db.DBConfigFromFile;
import org.apache.hadoop.ozone.OzoneConfigKeys;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.om.helpers.OmBucketInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyArgs;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.om.helpers.OmVolumeArgs;
import org.apache.hadoop.ozone.om.helpers.OpenKeySession;
import org.apache.hadoop.ozone.om.helpers.RepeatedOmKeyInfo;
import org.apache.hadoop.ozone.om.request.TestOMRequestUtils;
import org.apache.hadoop.util.Time;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Test Key Trash Service.
 * <p>
 * This test does the things including:
 * 1. UTs for list trash.
 * 2. UTs for recover trash.
 * 3. UTs for empty trash.
 * <p>
 */
public class TestTrashService {

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  private KeyManager keyManager;
  private OmMetadataManagerImpl omMetadataManager;
  private String volumeName;
  private String bucketName;
  private long trashRecoveryInterval;
  OzoneConfiguration configuration;

  @Before
  public void setup() throws IOException {
    configuration = new OzoneConfiguration();

    File folder = tempFolder.newFolder();
    if (!folder.exists()) {
      Assert.assertTrue(folder.mkdirs());
    }
    System.setProperty(DBConfigFromFile.CONFIG_DIR, "/");
    ServerUtils.setOzoneMetaDirPath(configuration, folder.toString());

    // Default is 1,000 validate before setting lower for tests below
    Assert.assertEquals(1000, OzoneConfigKeys.OZONE_CLIENT_LIST_TRASH_KEYS_MAX_DEFAULT);
    configuration.setInt(OzoneConfigKeys.OZONE_CLIENT_LIST_TRASH_KEYS_MAX, 100);
    configuration.set(OMConfigKeys.OZONE_OM_DB_DIRS, folder.getAbsolutePath());

    // Default is 120 minutes
    trashRecoveryInterval = OzoneConfigKeys.OZONE_CLIENT_TRASH_RECOVERY_INTERVAL_DEFAULT;
    Assert.assertEquals(120, trashRecoveryInterval);

    omMetadataManager = new OmMetadataManagerImpl(configuration);

    keyManager = new KeyManagerImpl(
        new ScmBlockLocationTestingClient(null, null, 0),
        omMetadataManager, configuration, UUID.randomUUID().toString(), null);
    keyManager.start(configuration);

    volumeName = UUID.randomUUID().toString();
    bucketName = UUID.randomUUID().toString();
  }

  @Test (expected = Exception.class)
  public void testListTrashFailsWithBucketTrashDisabled() throws Exception {
    int testKeys = 120;
    int maxKeys = 100;

    for (int i = 0; i < testKeys; i++) {
      String keyName = String.format("key%s",
        RandomStringUtils.randomAlphanumeric(5));

      createAndDeleteKey(volumeName, bucketName, false, keyName, 1);
    }

    List<RepeatedOmKeyInfo> results =
      keyManager.listTrash(volumeName, bucketName, null, null, maxKeys);

    Assert.assertEquals(maxKeys,results.size());
  }

  @Test
  public void testListTrashReturnsUnderMaxKeys() throws Exception {
    int testKeys = 10;
    int maxKeys = 5;

    for (int i = 0; i < testKeys; i++) {
      String keyName = String.format("key%s",
        RandomStringUtils.randomAlphanumeric(5));

      createAndDeleteKey(volumeName, bucketName, true, keyName, 1);
    }

    List<RepeatedOmKeyInfo> results =
      keyManager.listTrash(volumeName,bucketName,null,null, maxKeys);

    Assert.assertEquals(maxKeys,results.size());
  }

  @Test
  public void testListTrashReturnsMaxKeys() throws Exception {
    int testKeys = 120;
    int maxKeys = 100;

    for (int i = 0; i < testKeys; i++) {
      String keyName = String.format("key%s",
        RandomStringUtils.randomAlphanumeric(5));

      createAndDeleteKey(volumeName, bucketName, true, keyName, 1);
    }

    List<RepeatedOmKeyInfo> results =
      keyManager.listTrash(volumeName, bucketName, null, null, maxKeys);

    Assert.assertEquals(maxKeys,results.size());
  }

  @Test
  public void testListTrashKeysBelowZeroReturnsZero() throws Exception {
    int testKeys = 10;
    int maxKeys = -1;

    for (int i = 0; i < testKeys; i++) {
      String keyName = String.format("key%s",
        RandomStringUtils.randomAlphanumeric(5));

      createAndDeleteKey(volumeName, bucketName, true, keyName,1);
    }

    List<RepeatedOmKeyInfo> results =
      keyManager.listTrash(volumeName, bucketName, null, null, maxKeys);
    Assert.assertEquals(0, results.size());
  }

  @Test
  public void testListTrashReturnsZeroMaxKeys() throws Exception {
    int testKeys = 10;
    int maxKeys = 0;

    for (int i = 0; i < testKeys; i++) {
      String keyName = String.format("key%s",
        RandomStringUtils.randomAlphanumeric(5));

      createAndDeleteKey(volumeName, bucketName, true, keyName,1);
    }

    List<RepeatedOmKeyInfo> results =
      keyManager.listTrash(volumeName, bucketName, null, null, maxKeys);
    Assert.assertEquals(maxKeys, results.size());
  }

  @Test (expected = Exception.class)
  public void testListTrashFailsWithAboveClusterMaxKeysRequested() throws Exception {
    int testKeys = 10;
    int maxKeys = 101;

    for (int i = 0; i < testKeys; i++) {
      String keyName=String.format("key%s",
        RandomStringUtils.randomAlphanumeric(5));

      createAndDeleteKey(volumeName, bucketName, true, keyName, 1);
    }

    // This should fail with 101 keys requested and cluster limit set to 100
    List<RepeatedOmKeyInfo> results =
      keyManager.listTrash(volumeName,bucketName,null,null, maxKeys);
  }

  @Test(expected = Exception.class)
  public void testListTrashFailsWithInvalidVolumeName() throws Exception {
    int testKeys = 10;
    int maxKeys = 5;

    for (int i = 0; i < testKeys; i++) {
      String keyName = String.format("key%s",
        RandomStringUtils.randomAlphanumeric(5));

      createAndDeleteKey(volumeName, bucketName, true, keyName, 1);
    }

    List<RepeatedOmKeyInfo> results =
      keyManager.listTrash("fakeVolume", bucketName, null, null, maxKeys);
  }

  @Test(expected = Exception.class)
  public void testListTrashFailsWithInvalidBucketName()throws Exception {
    int testKeys = 10;
    int maxKeys = 5;

    for (int i = 0; i < testKeys; i++) {
      String keyName = String.format("key%s",
        RandomStringUtils.randomAlphanumeric(5));

      createAndDeleteKey(volumeName, bucketName, true, keyName, 1);
    }

    List<RepeatedOmKeyInfo> results =
      keyManager.listTrash(volumeName, "fakeBucket", null, null, maxKeys);
  }

  @Test
  public void testListTrashWithKeyPrefix() throws Exception {
    int testKeys = 50;
    int maxKeys = 100;

    // Prefixed with 'key'
    for (int i = 0; i < testKeys; i++) {
      String keyName = String.format("key%s",
        RandomStringUtils.randomAlphanumeric(5));

      createAndDeleteKey(volumeName, bucketName, true, keyName, 1);
    }

    // Prefixed with 'example'
    for (int i = 0; i < testKeys; i++) {
      String keyName = String.format("example%s",
        RandomStringUtils.randomAlphanumeric(5));

      createAndDeleteKey(volumeName, bucketName, true, keyName, 1);
    }

    // List only 'example' prefixed deleted keys
    List<RepeatedOmKeyInfo> results =
      keyManager.listTrash(volumeName, bucketName, null, "example", maxKeys);

    Assert.assertEquals(testKeys,results.size());
  }

  @Test
  public void testListTrashWithStartKey() throws Exception {
    int maxKeys = 100;

    createAndDeleteKey(volumeName, bucketName, true, "key1test", 1);
    createAndDeleteKey(volumeName, bucketName, true, "key2test", 1);
    createAndDeleteKey(volumeName, bucketName, true, "key3test", 1);
    createAndDeleteKey(volumeName, bucketName, true, "key4test", 1);
    createAndDeleteKey(volumeName, bucketName, true, "key5test", 1);

    // List deleted keys after the start key 'key3test'
    List<RepeatedOmKeyInfo> results =
      keyManager.listTrash(volumeName, bucketName, "key3test", null, maxKeys);

    Assert.assertEquals(2,results.size());
  }

  @Test
  public void testListTrashReturnZeroPastRecovery() throws Exception {
    int maxKeys = 100;

    // set recovery window to 2 hours ago
    trashRecoveryInterval = Time.now() - 7200000;
    configuration.setLong(OzoneConfigKeys.OZONE_CLIENT_TRASH_RECOVERY_INTERVAL, trashRecoveryInterval);

    createAndDeleteKey(volumeName, bucketName, true, "key1test", 1);
    createAndDeleteKey(volumeName, bucketName, true, "key2test", 1);
    createAndDeleteKey(volumeName, bucketName, true, "key3test", 1);
    createAndDeleteKey(volumeName, bucketName, true, "key4test", 1);
    createAndDeleteKey(volumeName, bucketName, true, "key5test", 1);

    // List deleted keys after the start key 'key3test'
    List<RepeatedOmKeyInfo> results =
      keyManager.listTrash(volumeName, bucketName, null , null, maxKeys);

    System.out.printf("Recovery time: %s%n", trashRecoveryInterval);
    printKeyInfo(results);

    Assert.assertEquals(0,results.size());
  }

  @Test
  public void testRecoverTrash() throws IOException {
    String keyName = "testKey";
    String destinationBucket = "destBucket";
    createAndDeleteKey(volumeName, bucketName, true, keyName, 1);

    boolean recoverOperation = omMetadataManager
        .recoverTrash(volumeName, bucketName, keyName, destinationBucket);
    Assert.assertTrue(recoverOperation);
  }

  private void createAndDeleteKey(String volumeName, String bucketName, Boolean trashEnabled, String keyName,
    int numBlocks) throws IOException {

    TestOMRequestUtils.addVolumeToOM(keyManager.getMetadataManager(),
        OmVolumeArgs.newBuilder()
            .setOwnerName("owner")
            .setAdminName("admin")
            .setVolume(volumeName)
            .build());

    TestOMRequestUtils.addBucketToOM(keyManager.getMetadataManager(),
        OmBucketInfo.newBuilder()
            .setVolumeName(volumeName)
            .setBucketName(bucketName)
            .addMetadata(OzoneConsts.TRASH_FLAG, trashEnabled.toString())
            .build());

    OmKeyArgs keyArgs = new OmKeyArgs.Builder()
        .setVolumeName(volumeName)
        .setBucketName(bucketName)
        .setKeyName(keyName)
        .setAcls(Collections.emptyList())
        .setLocationInfoList(new ArrayList<>())
        .build();

    // Open, Commit and Delete the Keys in the KeyManager.
    OpenKeySession session = keyManager.openKey(keyArgs);
    for (int i = 0; i < numBlocks; i++) {
      keyArgs.addLocationInfo(
        keyManager.allocateBlock(keyArgs,session.getId(), new ExcludeList()));
    }
    keyManager.commitKey(keyArgs,session.getId());
    keyManager.deleteKey(keyArgs);
  }

  private void printKeyInfo(List<RepeatedOmKeyInfo> results) {
    for (RepeatedOmKeyInfo keyInfo: results) {
      List<OmKeyInfo> infoList = keyInfo.getOmKeyInfoList();
      for (OmKeyInfo k : infoList) {
        System.out.printf("Volume Name: %s%n", k.getVolumeName());
        System.out.printf("Bucket Name: %s%n", k.getBucketName());
        System.out.printf("Key Name: %s%n", k.getKeyName());
        System.out.printf("Key Modification Time: %s%n", k.getModificationTime());
        System.out.println("");
      }
    }
  }

}
