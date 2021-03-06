/**
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.hadoop.fs.gcs;

import com.google.cloud.hadoop.gcsio.GoogleCloudStorageFileSystem;
import com.google.cloud.hadoop.gcsio.InMemoryGoogleCloudStorage;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * GoogleHadoopFileSystemTestHelper contains helper methods and factory methods for setting up the
 * test instances used for various unit and integration tests.
 */
public class GoogleHadoopFileSystemTestHelper {
  /**
   * Creates an instance of a bucket-rooted GoogleHadoopFileSystemBase using an in-memory
   * underlying store.
   */
  public static FileSystem createInMemoryGoogleHadoopFileSystem()
      throws IOException {
    GoogleCloudStorageFileSystem memoryGcsFs =
        new GoogleCloudStorageFileSystem(new InMemoryGoogleCloudStorage());
    GoogleHadoopFileSystem ghfs = new GoogleHadoopFileSystem(memoryGcsFs);
    initializeInMemoryFileSystem(ghfs, "gs:/");
    return ghfs;
  }

  /**
   * Creates an instance of a global-rooted GoogleHadoopFileSystemBase using an in-memory
   * underlying store.
   */
  public static FileSystem createInMemoryGoogleHadoopGlobalRootedFileSystem()
      throws IOException {
    GoogleCloudStorageFileSystem memoryGcsFs =
        new GoogleCloudStorageFileSystem(new InMemoryGoogleCloudStorage());
    GoogleHadoopFileSystemBase ghfs = new GoogleHadoopGlobalRootedFileSystem(memoryGcsFs);
    initializeInMemoryFileSystem(ghfs, "gsg://bucket-should-be-ignored");
    return ghfs;
  }

  /**
   * Helper for plumbing through an initUri and creating the proper Configuration object.
   * Calls FileSystem.initialize on {@code ghfs}.
   */
  private static void initializeInMemoryFileSystem(FileSystem ghfs, String initUriString)
      throws IOException {
    URI initUri;
    try {
      initUri = new URI(initUriString);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(e);
    }
    String systemBucketName = "fake-test-system-bucket";
    Configuration config = new Configuration();
    config.set(GoogleHadoopFileSystemBase.GCS_SYSTEM_BUCKET_KEY, systemBucketName);
    config.setBoolean(GoogleHadoopFileSystemBase.GCS_CREATE_SYSTEM_BUCKET_KEY, true);
    ghfs.initialize(initUri, config);
  }
}
