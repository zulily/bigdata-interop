/**
 * Copyright 2013 Google Inc. All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 *    
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.hadoop.gcsio;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

import java.util.Date;
import java.util.Map;
import java.util.Objects;

/**
 * Contains information about an item in Google Cloud Storage.
 */
public class GoogleCloudStorageItemInfo {
  // Info about the root of GCS namespace.
  public static final GoogleCloudStorageItemInfo ROOT_INFO =
      new GoogleCloudStorageItemInfo(StorageResourceId.ROOT, 0, 0, null, null);

  // Instead of returning null metadata, we'll return this map.
  private static final Map<String, byte[]> EMPTY_METADATA = ImmutableMap.of();

  // The Bucket and maybe StorageObject names of the GCS "item" referenced by this object. Not null.
  private final StorageResourceId resourceId;

  // Creation time of this item.
  // Time is expressed as milliseconds since January 1, 1970 UTC.
  private final long creationTime;

  // Size of an object (number of bytes).
  // Size is -1 for items that do not exist.
  private final long size;

  // Location of this item.
  private final String location;

  // Storage class of this item.
  private final String storageClass;

  // User-supplied metadata.
  private final Map<String, byte[]> metadata;
  private final long contentGeneration;
  private final long metaGeneration;

  /**
   * Constructs an instance of GoogleCloudStorageItemInfo.
   *
   * @param resourceId identifies either root, a Bucket, or a StorageObject
   * @param creationTime Time when object was created (milliseconds since January 1, 1970 UTC).
   * @param size Size of the given object (number of bytes) or -1 if the object does not exist.
   */
  public GoogleCloudStorageItemInfo(StorageResourceId resourceId,
      long creationTime, long size, String location, String storageClass) {
    this(
        resourceId,
        creationTime,
        size,
        location,
        storageClass,
        ImmutableMap.<String, byte[]>of(),
        0 /* content generation */,
        0 /* meta generation */);
  }

  /**
   * Constructs an instance of GoogleCloudStorageItemInfo.
   *
   * @param resourceId identifies either root, a Bucket, or a StorageObject
   * @param creationTime Time when object was created (milliseconds since January 1, 1970 UTC).
   * @param size Size of the given object (number of bytes) or -1 if the object does not exist.
   * @param metadata User-supplied object metadata for this object.
   */
  public GoogleCloudStorageItemInfo(
      StorageResourceId resourceId,
      long creationTime,
      long size,
      String location,
      String storageClass,
      Map<String, byte[]> metadata,
      long contentGeneration,
      long metaGeneration) {
    Preconditions.checkArgument(resourceId != null,
        "resourceId must not be null! Use StorageResourceId.ROOT to represent GCS root.");
    this.resourceId = resourceId;
    this.creationTime = creationTime;
    this.size = size;
    this.location = location;
    this.storageClass = storageClass;
    if (metadata == null) {
      this.metadata = EMPTY_METADATA;
    } else {
      this.metadata = metadata;
    }
    this.contentGeneration = contentGeneration;
    this.metaGeneration = metaGeneration;
  }

  /**
   * Gets bucket name of this item.
   */
  public String getBucketName() {
    return resourceId.getBucketName();
  }

  /**
   * Gets object name of this item.
   */
  public String getObjectName() {
    return resourceId.getObjectName();
  }

  /**
   * Gets the resourceId which holds the (possibly null) bucketName and objectName of this object.
   */
  public StorageResourceId getResourceId() {
    return resourceId;
  }

  /**
   * Gets creation time of this item.
   *
   * Time is expressed as milliseconds since January 1, 1970 UTC.
   */
  public long getCreationTime() {
    return creationTime;
  }

  /**
   * Gets size of this item (number of bytes). Returns -1 if the object
   * does not exist.
   */
  public long getSize() {
    return size;
  }

  /**
   * Gets location of this item.
   *
   * Note: Location is only supported for buckets. The value is always null for objects.
   */
  public String getLocation() {
    return location;
  }

  /**
   * Gets storage class of this item.
   *
   * Note: Storage-class is only supported for buckets. The value is always null for objects.
   */
  public String getStorageClass() {
    return storageClass;
  }

  /**
   * Gets user-supplied metadata for this item.
   *
   * Note: metadata is only supported for objects. This value is always an empty map for buckets.
   */
  public Map<String, byte[]> getMetadata() {
    return metadata;
  }

  /**
   * Indicates whether this item is a bucket. Root is not considered to be a bucket.
   */
  public boolean isBucket() {
    return resourceId.isBucket();
  }

  /**
   * Indicates whether this item refers to the GCS root (gs://).
   */
  public boolean isRoot() {
    return resourceId.isRoot();
  }

  /**
   * Indicates whether this item exists.
   */
  public boolean exists() {
    return size >= 0;
  }

  /**
   * Get the content generation of the object.
   */
  public long getContentGeneration() {
    return contentGeneration;
  }

  /**
   * Get the meta generation of the object.
   */
  public long getMetaGeneration() {
    return metaGeneration;
  }

  /**
   * Gets string representation of this instance.
   */
  @Override
  public String toString() {
    if (exists()) {
      return String.format("%s: created on: %s",
          resourceId, (new Date(creationTime)).toString());
    } else {
      return String.format("%s: exists: no", resourceId.toString());
    }
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof GoogleCloudStorageItemInfo) {
      GoogleCloudStorageItemInfo other = (GoogleCloudStorageItemInfo) obj;
      return resourceId.equals(other.resourceId) 
          && creationTime == other.creationTime 
          && size == other.size 
          && Objects.equals(location, other.location) 
          && Objects.equals(storageClass, other.storageClass)
          && metaGeneration == other.metaGeneration
          && contentGeneration == other.contentGeneration;
    }
    return false;
  }
}
