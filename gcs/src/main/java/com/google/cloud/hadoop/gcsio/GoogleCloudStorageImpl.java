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

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.GoogleUtils;
import com.google.api.client.googleapis.batch.json.JsonBatchCallback;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.apache.ApacheHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.BackOff;
import com.google.api.client.util.Data;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.client.util.Sleeper;
import com.google.api.services.storage.Storage;
import com.google.api.services.storage.model.Bucket;
import com.google.api.services.storage.model.Buckets;
import com.google.api.services.storage.model.Objects;
import com.google.api.services.storage.model.StorageObject;
import com.google.cloud.hadoop.util.ApiErrorExtractor;
import com.google.cloud.hadoop.util.ClientRequestHelper;
import com.google.cloud.hadoop.util.LogUtil;
import com.google.cloud.hadoop.util.RetryHttpInitializer;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.http.HttpHost;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.channels.WritableByteChannel;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Provides read/write access to Google Cloud Storage (GCS), using Java nio channel semantics.
 * This is a basic implementation of the GoogleCloudStorage interface which mostly delegates through
 * to the appropriate API call(s) via the generated JSON API client, while adding reliability and
 * performance features such as setting up low-level retries, translating low-level exceptions,
 * and request batching.
 */
public class GoogleCloudStorageImpl
    implements GoogleCloudStorage {
  // Pseudo path delimiter.
  //
  // GCS does not implement full concept of file system paths but it does expose
  // some notion of a delimiter that can be used with Storage.Objects.List to
  // control which items are listed.
  public static final String PATH_DELIMITER = "/";

  // Number of retries to make when waiting for a bucket to be empty.
  public static final int BUCKET_EMPTY_MAX_RETRIES = 20;

  // Duration of wait (in milliseconds) per retry for a bucket to be empty.
  public static final int BUCKET_EMPTY_WAIT_TIME_MS = 500;

  // JSON factory used for formatting GCS JSON API payloads.
  private static final JsonFactory JSON_FACTORY = new JacksonFactory();

  // Logger.
  private static final LogUtil log = new LogUtil(GoogleCloudStorage.class);

  // com.google.common.net cannot be imported here for various dependency reasons.
  private static final String OCTECT_STREAM_MEDIA_TYPE = "application/octet-stream";

  // Maximum number of times to retry deletes in the case of precondition failures.
  private static final int MAXIMUM_PRECONDITION_FAILURES_IN_DELETE = 4;

  // Determine if a given IOException is due to rate-limiting.
  private final Predicate<IOException> isRateLimitedException = new Predicate<IOException>() {
    @Override
    public boolean apply(IOException e) {
      return errorExtractor.rateLimited(e);
    }
  };

  // A function to encode metadata map values
  private static final Function<byte[], String> ENCODE_METADATA_VALUES =
      new Function<byte[], String>() {
        @Override
        public String apply(byte[] bytes) {
          if (bytes == null) {
            return Data.NULL_STRING;
          } else {
            return BaseEncoding.base64().encode(bytes);
          }
        }
  };

  private static final Function<String, byte[]> DECODE_METADATA_VALUES =
      new Function<String, byte[]>() {
        @Override
        public byte[] apply(String value) {
          try {
            return BaseEncoding.base64().decode(value);
          } catch (IllegalArgumentException iae) {
            log.error("Failed to parse base64 encoded attribute value %s - %s", value, iae);
            return null;
          }
        }
  };

  /**
   * A factory for producing BackOff objects.
   */
  public static interface BackOffFactory {
    public static final BackOffFactory DEFAULT = new BackOffFactory() {
      @Override
      public BackOff newBackOff() {
        return new ExponentialBackOff();
      }
    };

    BackOff newBackOff();
  }

  // GCS access instance.
  private Storage gcs;

  // Thread-pool used for background tasks.
  private ExecutorService threadPool = Executors.newCachedThreadPool(
      new ThreadFactoryBuilder()
          .setNameFormat("gcs-async-channel-pool-%d")
          .setDaemon(true)
          .build());

  // Thread-pool for manual matching of metadata tasks.
  // TODO(user): Wire out GoogleCloudStorageOptions for these.
  private ExecutorService manualBatchingThreadPool = new ThreadPoolExecutor(
      10 /* base num threads */, 20 /* max num threads */, 10L /* keepalive time */,
      TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(),
      new ThreadFactoryBuilder()
          .setNameFormat("gcs-manual-batching-pool-%d")
          .setDaemon(true)
          .build());

  // Helper delegate for turning IOExceptions from API calls into higher-level semantics.
  private ApiErrorExtractor errorExtractor = new ApiErrorExtractor();

  // Helper for interacting with objects invovled with the API client libraries.
  private ClientRequestHelper<StorageObject> clientRequestHelper =
      new ClientRequestHelper<>();

  // Factory for BatchHelpers setting up BatchRequests; can be swapped out for testing purposes.
  private BatchHelper.Factory batchFactory = new BatchHelper.Factory();

  // Request initializer to use for batch and non-batch requests.
  private HttpRequestInitializer httpRequestInitializer;

  // Configuration values for this instance
  private final GoogleCloudStorageOptions storageOptions;

  // Object to use to perform sleep operations
  private Sleeper sleeper = Sleeper.DEFAULT;

  // BackOff objects are per-request, use this to make new ones.
  private BackOffFactory backOffFactory = BackOffFactory.DEFAULT;

  /**
   * Constructs an instance of GoogleCloudStorageImpl.
   *
   * @param credential OAuth2 credential that allows access to GCS
   * @throws IOException on IO error
   */
  public GoogleCloudStorageImpl(GoogleCloudStorageOptions options, Credential credential)
      throws IOException {
    Preconditions.checkArgument(options != null, "options must not be null");

    log.debug("GCS(%s)", options.getAppName());

    options.throwIfNotValid();

    this.storageOptions = options;

    Preconditions.checkArgument(credential != null, "credential must not be null");

    this.httpRequestInitializer = new RetryHttpInitializer(credential, options.getAppName());

    // HTTP transport used for interacting with Google APIs.
    ApacheHttpTransport.Builder httpTransportBuilder = new ApacheHttpTransport.Builder();
    try {
      httpTransportBuilder.trustCertificates(GoogleUtils.getCertificateTrustStore());
    } catch (GeneralSecurityException e) {
      log.error(e.getMessage(), e);
    }
    HttpHost proxyHost = options.getProxyHost();
    if (proxyHost != null) {
      httpTransportBuilder.setProxy(proxyHost);
    }

    // Create GCS instance.
    gcs = new Storage.Builder(
      httpTransportBuilder.build(), JSON_FACTORY, httpRequestInitializer)
        .setApplicationName(options.getAppName())
        .build();
  }

  /**
   * Constructs an instance of GoogleCloudStorageImpl.
   *
   * @param gcs Preconstructed Storage to use for I/O.
   */
  public GoogleCloudStorageImpl(GoogleCloudStorageOptions options, Storage gcs) {
    Preconditions.checkArgument(options != null, "options must not be null");

    log.debug("GCS(%s)", options.getAppName());

    options.throwIfNotValid();

    this.storageOptions = options;

    Preconditions.checkArgument(gcs != null, "gcs must not be null");

    this.gcs = gcs;

    // Normally used for batch requests, but not necessary for correctness.
    // TODO(user): Support custom initializers without Credentials.
    this.httpRequestInitializer = null;
  }

  @VisibleForTesting
  protected GoogleCloudStorageImpl() {
    this.storageOptions = GoogleCloudStorageOptions.newBuilder().build();
  }

  @VisibleForTesting
  void setThreadPool(ExecutorService threadPool) {
    this.threadPool = threadPool;
  }

  @VisibleForTesting
  void setManualBatchingThreadPool(ExecutorService manualBatchingThreadPool) {
    this.manualBatchingThreadPool = manualBatchingThreadPool;
  }

  @VisibleForTesting
  void setErrorExtractor(ApiErrorExtractor errorExtractor) {
    this.errorExtractor = errorExtractor;
  }

  @VisibleForTesting
  void setClientRequestHelper(
      ClientRequestHelper<StorageObject> clientRequestHelper) {
    this.clientRequestHelper = clientRequestHelper;
  }

  @VisibleForTesting
  void setBatchFactory(BatchHelper.Factory batchFactory) {
    this.batchFactory = batchFactory;
  }

  @VisibleForTesting
  GoogleCloudStorageOptions getStorageOptions() {
    return this.storageOptions;
  }

  @VisibleForTesting
  void setSleeper(Sleeper sleeper) {
    this.sleeper = sleeper;
  }

  @VisibleForTesting
  void setBackOffFactory(BackOffFactory factory) {
    backOffFactory = factory;
  }

  @Override
  public WritableByteChannel create(StorageResourceId resourceId, CreateObjectOptions options)
      throws IOException {
    log.debug("create(%s)", resourceId);
    Preconditions.checkArgument(resourceId.isStorageObject(),
        "Expected full StorageObject id, got " + resourceId);

    /**
     * When performing mutations in GCS, even when we aren't concerned with parallel writers,
     * we need to protect ourselves from what appear to be out-of-order writes to the writer. These
     * most commonly manifest themselves as a sequence of:
     * 1) Perform mutation M1 on object O1, which results in an HTTP 503 error,
     *    but can be any 5XX class error.
     * 2) Retry mutation M1, which yields a 200 OK
     * 3) Perform mutation M2 on O1 which yields a 200 OK
     * 4) Some time later, get O1 and see M1 and not M2, even though M2 appears to have happened
     *    later.
     *
     * To counter this we need to perform mutations with a condition attached, always.
     *
     * To perform a mutation with a condition, we first must get the content generation of the
     * current object. Once we have the current generation, we will create a marker file
     * conditionally with an ifGenerationMatch. We will then create the final object only if the
     * generation matches the marker file.
     */
    // TODO(user): Have createEmptyObject return enough information to use that instead.
    Optional<Long> markerGeneration = Optional.absent();
    BackOff backOff = backOffFactory.newBackOff();
    long backOffSleep = 0L;

    do {
      if (backOffSleep != 0) {
        try {
          sleeper.sleep(backOffSleep);
        } catch (InterruptedException ie) {
          throw new IOException(String.format(
              "Interrupted while sleeping for backoff in create of %s", resourceId));
        }
      }

      backOffSleep = backOff.nextBackOffMillis();
      Storage.Objects.Insert insertObject = prepareEmptyInsert(resourceId, options);
      GoogleCloudStorageItemInfo info = getItemInfo(resourceId);

      if (!info.exists()) {
        insertObject.setIfGenerationMatch(0L);
      } else if (info.exists() && options.overwriteExisting()) {
        long generation = info.getContentGeneration();
        Preconditions.checkState(
            generation != 0, "Generation should not be 0 for an existing item");
        insertObject.setIfGenerationMatch(generation);
      } else {
        throw new IOException(String.format("Object %s already exists", resourceId.toString()));
      }

      try {
        StorageObject result = insertObject.execute();
        markerGeneration = Optional.of(result.getGeneration());
      } catch (IOException ioe) {
        if (errorExtractor.preconditionNotMet(ioe)) {
          log.info(
              "Retrying marker file creation. Retrying according to backoff policy, %s - %s",
              resourceId,
              ioe);
        } else {
          throw ioe;
        }
      }
    } while (!markerGeneration.isPresent() && backOffSleep != BackOff.STOP);

    if (backOffSleep == BackOff.STOP) {
      throw new IOException(
          String.format(
              "Retries exhausted while attempting to create marker file for %s", resourceId));
    }

    ObjectWriteConditions writeConditions =
        new ObjectWriteConditions(markerGeneration, Optional.<Long>absent());

    Map<String, String> rewrittenMetadata =
        Maps.transformValues(options.getMetadata(), ENCODE_METADATA_VALUES);

    GoogleCloudStorageWriteChannel channel = new GoogleCloudStorageWriteChannel(
        threadPool,
        gcs,
        clientRequestHelper,
        resourceId.getBucketName(),
        resourceId.getObjectName(),
        storageOptions.getWriteChannelOptions(),
        writeConditions,
        rewrittenMetadata);

    channel.initialize();

    return channel;
  }

  /**
   * See {@link GoogleCloudStorage#create(StorageResourceId)} for details about expected behavior.
   */
  @Override
  public WritableByteChannel create(StorageResourceId resourceId)
      throws IOException {
    log.debug("create(%s)", resourceId);
    Preconditions.checkArgument(resourceId.isStorageObject(),
        "Expected full StorageObject id, got " + resourceId);

    return create(resourceId, CreateObjectOptions.DEFAULT);
  }

  @Override
  public void createEmptyObject(StorageResourceId resourceId, CreateObjectOptions options)
      throws IOException {
    Preconditions.checkArgument(resourceId.isStorageObject(),
        "Expected full StorageObject id, got " + resourceId);

    Storage.Objects.Insert insertObject = prepareEmptyInsert(resourceId, options);
    insertObject.execute();
  }

  /**
   * See {@link GoogleCloudStorage#createEmptyObject(StorageResourceId)} for details about
   * expected behavior.
   */
  @Override
  public void createEmptyObject(StorageResourceId resourceId)
      throws IOException {
    log.debug("createEmptyObject(%s)", resourceId);
    Preconditions.checkArgument(resourceId.isStorageObject(),
        "Expected full StorageObject id, got " + resourceId);
    createEmptyObject(resourceId, CreateObjectOptions.DEFAULT);
  }

  @Override
  public void createEmptyObjects(List<StorageResourceId> resourceIds, CreateObjectOptions options)
      throws IOException {
    // TODO(user): This method largely follows a pattern similar to
    // deleteObjects(List<StorageResourceId>); extract a generic method for both.
    log.debug("createEmptyObjects(%s)", resourceIds);

    // Validate that all the elements represent StorageObjects.
    for (StorageResourceId resourceId : resourceIds) {
      Preconditions.checkArgument(resourceId.isStorageObject(),
          "Expected full StorageObject names only, got: '%s'", resourceId);
    }

    // Gather exceptions to wrap in a composite exception at the end.
    final List<IOException> innerExceptions =
        Collections.synchronizedList(new ArrayList<IOException>());
    final CountDownLatch latch = new CountDownLatch(resourceIds.size());
    for (final StorageResourceId resourceId : resourceIds) {
      final Storage.Objects.Insert insertObject = prepareEmptyInsert(resourceId, options);
      manualBatchingThreadPool.execute(new Runnable() {
        @Override
        public void run() {
          try {
            insertObject.execute();
            log.debug("Successfully inserted %s", resourceId.toString());
          } catch (IOException ioe) {
            innerExceptions.add(wrapException(ioe, "Error inserting",
                resourceId.getBucketName(), resourceId.getObjectName()));
          } catch (Throwable t) {
            innerExceptions.add(wrapException(new IOException(t), "Error inserting",
                resourceId.getBucketName(), resourceId.getObjectName()));
          } finally {
            latch.countDown();
          }
        }
      });
    }

    try {
      latch.await();
    } catch (InterruptedException ie) {
      throw new IOException(ie);
    }

    if (!innerExceptions.isEmpty()) {
      throw GoogleCloudStorageExceptions.createCompositeException(innerExceptions);
    }
  }

  /**
   * See {@link GoogleCloudStorage#createEmptyObjects(List)} for details about
   * expected behavior.
   */
  @Override
  public void createEmptyObjects(List<StorageResourceId> resourceIds)
      throws IOException {
    createEmptyObjects(resourceIds, CreateObjectOptions.DEFAULT);
  }

  /**
   * See {@link GoogleCloudStorage#open(StorageResourceId)} for details about expected behavior.
   */
  @Override
  public SeekableReadableByteChannel open(StorageResourceId resourceId)
      throws IOException {
    log.debug("open(%s)", resourceId);
    Preconditions.checkArgument(resourceId.isStorageObject(),
        "Expected full StorageObject id, got " + resourceId);

    // The underlying channel doesn't initially read data which means that we won't see a
    // FileNotFoundException until read is called. As a result, in order to find out if the object
    // exists, we'll need to do an RPC (metadata or data). A metadata check should be a less
    // expensive operation than a read data operation.
    if (!getItemInfo(resourceId).exists()) {
      throw GoogleCloudStorageExceptions.getFileNotFoundException(
          resourceId.getBucketName(), resourceId.getObjectName());
    }

    return new GoogleCloudStorageReadChannel(
        gcs,
        resourceId.getBucketName(),
        resourceId.getObjectName(),
        errorExtractor,
        clientRequestHelper);
  }

  /**
   * See {@link GoogleCloudStorage#create(String)} for details about expected behavior.
   */
  @Override
  public void create(String bucketName)
      throws IOException {
    log.debug("create(%s)", bucketName);
    Preconditions.checkArgument(!Strings.isNullOrEmpty(bucketName),
        "bucketName must not be null or empty");

    Bucket bucket = new Bucket();
    bucket.setName(bucketName);
    Storage.Buckets.Insert insertBucket =
        gcs.buckets().insert(storageOptions.getProjectId(), bucket);
    // TODO(user): To match the behavior of throwing FileNotFoundException for 404, we probably
    // want to throw org.apache.commons.io.FileExistsException for 409 here.

    OperationWithRetry<Storage.Buckets.Insert, Bucket> operation =
        new OperationWithRetry<>(
            sleeper,
            backOffFactory.newBackOff(),
            insertBucket,
            isRateLimitedException);

    operation.execute();
  }

  /**
   * See {@link GoogleCloudStorage#deleteBuckets(List<String>)} for details about expected behavior.
   */
  @Override
  public void deleteBuckets(List<String> bucketNames)
      throws IOException {
    log.debug("deleteBuckets(%s)", bucketNames.toString());

    // Validate all the inputs first.
    for (String bucketName : bucketNames) {
      Preconditions.checkArgument(!Strings.isNullOrEmpty(bucketName),
          "bucketName must not be null or empty");
    }

    // Gather exceptions to wrap in a composite exception at the end.
    final List<IOException> innerExceptions = new ArrayList<>();

    for (final String bucketName : bucketNames) {
      final Storage.Buckets.Delete deleteBucket = gcs.buckets().delete(bucketName);
      OperationWithRetry<Storage.Buckets.Delete, Void> bucketDeleteOperation =
          new OperationWithRetry<> (
              sleeper,
              backOffFactory.newBackOff(),
              deleteBucket,
              isRateLimitedException);

      try {
        bucketDeleteOperation.execute();
      } catch (IOException ioe) {
        if (errorExtractor.itemNotFound(ioe)) {
          log.debug("delete(%s) : not found", bucketName);
          innerExceptions.add(GoogleCloudStorageExceptions.getFileNotFoundException(
              bucketName, null));
        } else {
          innerExceptions.add(wrapException(
              new IOException(ioe.toString()), "Error deleting", bucketName, null));
        }
      }
    }
    if (innerExceptions.size() > 0) {
      throw GoogleCloudStorageExceptions.createCompositeException(innerExceptions);
    }
  }

  /**
   * See {@link GoogleCloudStorage#deleteObjects(List<StorageResourceId>)} for details about
   * expected behavior.
   */
  @Override
  public void deleteObjects(List<StorageResourceId> fullObjectNames)
      throws IOException {
    log.debug("deleteObjects(%s)", fullObjectNames.toString());

    // Validate that all the elements represent StorageObjects.
    for (StorageResourceId fullObjectName : fullObjectNames) {
      Preconditions.checkArgument(fullObjectName.isStorageObject(),
          "Expected full StorageObject names only, got: " + fullObjectName.toString());
    }

    // Gather exceptions to wrap in a composite exception at the end.
    final List<IOException> innerExceptions = new ArrayList<>();
    BatchHelper batchHelper = batchFactory.newBatchHelper(
        httpRequestInitializer,
        gcs,
        storageOptions.getMaxRequestsPerBatch());

    for (final StorageResourceId fullObjectName : fullObjectNames) {
      queueSingleObjectDelete(fullObjectName, innerExceptions, batchHelper, 1);
    }

    do {
      batchHelper.flush();
    } while (!batchHelper.isEmpty());

    if (innerExceptions.size() > 0) {
      throw GoogleCloudStorageExceptions.createCompositeException(innerExceptions);
    }
  }

  private void queueSingleObjectDelete(
      final StorageResourceId fullObjectName,
      final List<IOException> innerExceptions,
      final BatchHelper batchHelper,
      final int attempt) throws IOException {

    final String bucketName = fullObjectName.getBucketName();
    final String objectName = fullObjectName.getObjectName();

    // We first need to get the current object version to issue a safe delete for only the
    // latest version of the object.
    Storage.Objects.Get getObject = gcs.objects().get(bucketName, objectName);
    batchHelper.queue(getObject, new JsonBatchCallback<StorageObject>() {
      @Override
      public void onSuccess(StorageObject storageObject, HttpHeaders httpHeaders)
          throws IOException {
        final Long generation = storageObject.getGeneration();
        Storage.Objects.Delete deleteObject =
            gcs.objects().delete(bucketName, objectName)
                .setIfGenerationMatch(generation);

        batchHelper.queue(deleteObject, new JsonBatchCallback<Void>() {
          @Override
          public void onSuccess(Void obj, HttpHeaders responseHeaders) {
            log.debug(
                "Successfully deleted %s at generation %s", fullObjectName.toString(), generation);
          }

          @Override
          public void onFailure(GoogleJsonError e, HttpHeaders responseHeaders) throws IOException {
            if (errorExtractor.itemNotFound(e)) {
              // Ignore item-not-found errors. We do not have to delete what we cannot find. This
              // error typically shows up when we make a request to delete something and the server
              // receives the request but we get a retry-able error before we get a response.
              // During a retry, we no longer find the item because the server had deleted
              // it already.
              log.debug("deleteObjects(%s) : delete not found", fullObjectName.toString());
            } else if (errorExtractor.preconditionNotMet(e)
                && attempt <= MAXIMUM_PRECONDITION_FAILURES_IN_DELETE) {
              log.info(
                  "Precondition not met while deleting %s at generation %s. Attempt %s. Retrying.",
                  fullObjectName.toString(),
                  generation,
                  attempt);
              queueSingleObjectDelete(
                  fullObjectName, innerExceptions, batchHelper, attempt + 1);
            } else {
              innerExceptions.add(wrapException(
                  new IOException(e.toString()),
                  String.format("Error deleting, stage 2 with generation %s", generation),
                  bucketName,
                  objectName));
            }
          }
        });
      }
      @Override
      public void onFailure(GoogleJsonError googleJsonError, HttpHeaders httpHeaders)
          throws IOException {
        if (errorExtractor.itemNotFound(googleJsonError)) {
          // If the the item isn't found, treat it the same as if it's not found in the delete
          // case: assume the user wanted the object gone and now it is.
          log.debug("deleteObjects(%s) : get not found", fullObjectName.toString());
        } else {
          innerExceptions.add(wrapException(
              new IOException(googleJsonError.toString()),
              "Error deleting, stage 1",
              bucketName,
              objectName));
        }
      }
    });
  }

  /**
   * Validates basic argument constraints like non-null, non-empty Strings, using {@code
   * Preconditions} in addition to checking for src/dst bucket existence and compatibility of bucket
   * properties such as location and storage-class.
   * @param gcsImpl A GoogleCloudStorage for retrieving bucket info via getItemInfo, but only if
   *     srcBucketName != dstBucketName; passed as a parameter so that this static method can be
   *     used by other implementations of GoogleCloudStorage which want to preserve the validation
   *     behavior of GoogleCloudStorageImpl, including disallowing cross-location copies.
   */
  static void validateCopyArguments(String srcBucketName, List<String> srcObjectNames,
      String dstBucketName, List<String> dstObjectNames, GoogleCloudStorage gcsImpl)
      throws IOException {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(srcBucketName),
        "srcBucketName must not be null or empty");
    Preconditions.checkArgument(!Strings.isNullOrEmpty(dstBucketName),
        "dstBucketName must not be null or empty");
    Preconditions.checkArgument(srcObjectNames != null,
        "srcObjectNames must not be null");
    Preconditions.checkArgument(dstObjectNames != null,
        "dstObjectNames must not be null");
    Preconditions.checkArgument(srcObjectNames.size() == dstObjectNames.size(),
        "Must supply same number of elements in srcObjectNames and dstObjectNames");

    // Avoid copy across locations or storage classes.
    if (!srcBucketName.equals(dstBucketName)) {
      GoogleCloudStorageItemInfo srcBucketInfo =
          gcsImpl.getItemInfo(new StorageResourceId(srcBucketName));
      if (!srcBucketInfo.exists()) {
        throw new FileNotFoundException("Bucket not found: " + srcBucketName);
      }

      GoogleCloudStorageItemInfo dstBucketInfo =
          gcsImpl.getItemInfo(new StorageResourceId(dstBucketName));
      if (!dstBucketInfo.exists()) {
        throw new FileNotFoundException("Bucket not found: " + dstBucketName);
      }

      if (!srcBucketInfo.getLocation().equals(dstBucketInfo.getLocation())) {
        throw new UnsupportedOperationException(
            "This operation is not supported across two different storage locations.");
      }

      if (!srcBucketInfo.getStorageClass().equals(dstBucketInfo.getStorageClass())) {
        throw new UnsupportedOperationException(
            "This operation is not supported across two different storage classes.");
      }
    }
    for (int i = 0; i < srcObjectNames.size(); i++) {
      Preconditions.checkArgument(!Strings.isNullOrEmpty(srcObjectNames.get(i)),
          "srcObjectName must not be null or empty");
      Preconditions.checkArgument(!Strings.isNullOrEmpty(dstObjectNames.get(i)),
          "dstObjectName must not be null or empty");
      if (srcBucketName.equals(dstBucketName)
          && srcObjectNames.get(i).equals(dstObjectNames.get(i))) {
        throw new IllegalArgumentException(String.format(
            "Copy destination must be different from source for %s.",
            StorageResourceId.createReadableString(srcBucketName, srcObjectNames.get(i))));
      }
    }
  }

  /**
   * See {@link GoogleCloudStorage#copy(String, List, String, List)} for details
   * about expected behavior.
   */
  @Override
  public void copy(final String srcBucketName, List<String> srcObjectNames,
      final String dstBucketName, List<String> dstObjectNames)
      throws IOException {
    validateCopyArguments(srcBucketName, srcObjectNames,
        dstBucketName, dstObjectNames, this);

    // Gather FileNotFoundExceptions for individual objects, but only throw a single combined
    // exception at the end.
    final List<IOException> innerExceptions = new ArrayList<>();

    // Perform the copy operations.
    BatchHelper batchHelper = batchFactory.newBatchHelper(
        httpRequestInitializer,
        gcs,
        storageOptions.getMaxRequestsPerBatch());

    for (int i = 0; i < srcObjectNames.size(); i++) {
      final String srcObjectName = srcObjectNames.get(i);
      final String dstObjectName = dstObjectNames.get(i);
      Storage.Objects.Copy copyObject = gcs.objects().copy(
          srcBucketName, srcObjectName,
          dstBucketName, dstObjectName,
          null);
      batchHelper.queue(copyObject, new JsonBatchCallback<StorageObject>() {
        @Override
        public void onSuccess(StorageObject obj, HttpHeaders responseHeaders) {
          log.debug("Successfully copied %s to %s",
              StorageResourceId.createReadableString(srcBucketName, srcObjectName),
              StorageResourceId.createReadableString(dstBucketName, dstObjectName));
        }

        @Override
        public void onFailure(GoogleJsonError e, HttpHeaders responseHeaders) {
          if (errorExtractor.itemNotFound(e)) {
            log.debug("copy(%s) : not found",
                StorageResourceId.createReadableString(srcBucketName, srcObjectName));
            innerExceptions.add(GoogleCloudStorageExceptions.getFileNotFoundException(
                srcBucketName, srcObjectName));
          } else {
            innerExceptions.add(wrapException(
                new IOException(e.toString()), "Error copying", srcBucketName, srcObjectName));
          }
        }
      });
    }
    // Execute any remaining requests not divisible by the max batch size.
    batchHelper.flush();

    if (innerExceptions.size() > 0) {
      throw GoogleCloudStorageExceptions.createCompositeException(innerExceptions);
    }
  }

  /**
   * Shared helper for actually dispatching buckets().list() API calls and accumulating paginated
   * results; these can then be used to either extract just their names, or to parse into full
   * GoogleCloudStorageItemInfos.
   */
  private List<Bucket> listBucketsInternal()
      throws IOException {
    log.debug("listBucketsInternal()");
    List<Bucket> allBuckets = new ArrayList<>();
    Storage.Buckets.List listBucket = gcs.buckets().list(storageOptions.getProjectId());

    // Set number of items to retrieve per call.
    listBucket.setMaxResults(storageOptions.getMaxListItemsPerCall());

    // Loop till we fetch all items.
    String pageToken = null;
    do {
      if (pageToken != null) {
        log.debug("listBucketsInternal: next page %s", pageToken);
        listBucket.setPageToken(pageToken);
      }

      Buckets items = listBucket.execute();

      // Accumulate buckets (if any).
      List<Bucket> buckets = items.getItems();
      if (buckets != null) {
        log.debug("listed %d items", buckets.size());
        allBuckets.addAll(buckets);
      }

      pageToken = items.getNextPageToken();
    } while (pageToken != null);

    return allBuckets;
  }

  /**
   * See {@link GoogleCloudStorage#listBucketNames()} for details about expected behavior.
   */
  @Override
  public List<String> listBucketNames()
      throws IOException {
    log.debug("listBucketNames()");
    List<String> bucketNames = new ArrayList<>();
    List<Bucket> allBuckets = listBucketsInternal();
    for (Bucket bucket : allBuckets) {
      bucketNames.add(bucket.getName());
    }
    return bucketNames;
  }

  /**
   * See {@link GoogleCloudStorage#listBucketInfo()} for details about expected behavior.
   */
  @Override
  public List<GoogleCloudStorageItemInfo> listBucketInfo()
      throws IOException {
    log.debug("listBucketInfo()");
    List<GoogleCloudStorageItemInfo> bucketInfos = new ArrayList<>();
    List<Bucket> allBuckets = listBucketsInternal();
    for (Bucket bucket : allBuckets) {
      bucketInfos.add(new GoogleCloudStorageItemInfo(
          new StorageResourceId(bucket.getName()), bucket.getTimeCreated().getValue(), 0,
          bucket.getLocation(), bucket.getStorageClass()));
    }
    return bucketInfos;
  }

  /**
   * Helper for creating a Storage.Objects.Copy object ready for dispatch given a bucket and
   * object for an empty object to be created. Caller must already verify that {@code resourceId}
   * represents a StorageObject and not a bucket.
   */
  private Storage.Objects.Insert prepareEmptyInsert(StorageResourceId resourceId,
      CreateObjectOptions createObjectOptions) throws IOException {
    StorageObject object = new StorageObject();
    object.setName(resourceId.getObjectName());
    Map<String, String> rewrittenMetadata =
        Maps.transformValues(createObjectOptions.getMetadata(), ENCODE_METADATA_VALUES);
    object.setMetadata(rewrittenMetadata);

    // Ideally we'd use EmptyContent, but Storage requires an AbstractInputStreamContent and not
    // just an HttpContent, so we'll just use the next easiest thing.
    ByteArrayContent emptyContent =
        new ByteArrayContent(OCTECT_STREAM_MEDIA_TYPE, new byte[0]);
    Storage.Objects.Insert insertObject = gcs.objects().insert(
        resourceId.getBucketName(), object, emptyContent);
    insertObject.setDisableGZipContent(true);
    clientRequestHelper.setDirectUploadEnabled(insertObject, true);

    if (!createObjectOptions.overwriteExisting()) {
      insertObject.setIfGenerationMatch(0L);
    }
    return insertObject;
  }

  /**
   * Helper for both listObjectNames and listObjectInfo, which executes the actual API calls to
   * get paginated lists, accumulating the StorageObjects and String prefixes into the params
   * {@code listedObjects} and {@code listedPrefixes}.
   *
   * @param bucketName bucket name
   * @param objectNamePrefix object name prefix or null if all objects in the bucket are desired
   * @param delimiter delimiter to use (typically "/"), otherwise null
   * @param listedObjects output parameter into which retrieved StorageObjects will be added
   * @param listedPrefixes output parameter into which retrieved prefixes will be added
   */
  private void listStorageObjectsAndPrefixes(
      String bucketName, String objectNamePrefix, String delimiter,
      List<StorageObject> listedObjects, List<String> listedPrefixes)
      throws IOException {
    log.debug("listStorageObjectsAndPrefixes(%s, %s, %s)", bucketName, objectNamePrefix, delimiter);
    Preconditions.checkArgument(!Strings.isNullOrEmpty(bucketName),
        "bucketName must not be null or empty");
    Preconditions.checkArgument(listedObjects != null,
        "Must provide a non-null container for listedObjects.");
    Preconditions.checkArgument(listedPrefixes != null,
        "Must provide a non-null container for listedPrefixes.");
    Storage.Objects.List listObject = gcs.objects().list(bucketName);

    // Set delimiter if supplied.
    if (delimiter != null) {
      listObject.setDelimiter(delimiter);
    }

    // Set number of items to retrieve per call.
    listObject.setMaxResults(storageOptions.getMaxListItemsPerCall());

    // Set prefix if supplied.
    if (!Strings.isNullOrEmpty(objectNamePrefix)) {
      listObject.setPrefix(objectNamePrefix);
    }

    // Loop till we fetch all items.
    String pageToken = null;
    Objects items;

    do {
      if (pageToken != null) {
        log.debug("listObjectNames: next page %s", pageToken);
        listObject.setPageToken(pageToken);
      }

      try {
        items = listObject.execute();
      } catch (IOException e) {
        if (errorExtractor.itemNotFound(e)) {
          log.debug("listObjectNames(%s, %s, %s): not found",
              bucketName, objectNamePrefix, delimiter);
          break;
        } else {
          throw wrapException(e, "Error listing", bucketName, objectNamePrefix);
        }
      }

      // Add prefixes (if any).
      List<String> prefixes = items.getPrefixes();
      if (prefixes != null) {
        log.debug("listed %d prefixes", prefixes.size());
        listedPrefixes.addAll(prefixes);
      }

      // Add object names (if any).
      List<StorageObject> objects = items.getItems();
      if (objects != null) {
        log.debug("listed %d objects", objects.size());

        // Although GCS does not implement a file system, it treats objects that end
        // in delimiter as different from other objects when listing objects.
        //
        // If caller sends foo/ as the prefix, foo/ is returned as an object name.
        // That is inconsistent with listing items in a directory.
        // Not sure if that is a bug in GCS or the intended behavior.
        //
        // In this case, we do not want foo/ in the returned list because we want to
        // keep the behavior more like a file system without calling it as such.
        // Therefore, we filter out such entry.

        // Determine if the caller sent a directory name as a prefix.
        boolean objectPrefixEndsWithDelimiter =
            !Strings.isNullOrEmpty(objectNamePrefix) && objectNamePrefix.endsWith(PATH_DELIMITER);

        for (StorageObject object : objects) {
          String objectName = object.getName();
          if (!objectPrefixEndsWithDelimiter
              || (objectPrefixEndsWithDelimiter && !objectName.equals(objectNamePrefix))) {
            listedObjects.add(object);
          }
        }
      }
      pageToken = items.getNextPageToken();
    } while (pageToken != null);
  }

  /**
   * See {@link GoogleCloudStorage#listObjectNames(String, String, String)} for details about
   * expected behavior.
   */
  @Override
  public List<String> listObjectNames(
      String bucketName, String objectNamePrefix, String delimiter)
      throws IOException {
    log.debug("listObjectNames(%s, %s, %s)", bucketName, objectNamePrefix, delimiter);

    // Helper will handle going through pages of list results and accumulating them.
    List<StorageObject> listedObjects = new ArrayList<>();
    List<String> listedPrefixes = new ArrayList<>();
    listStorageObjectsAndPrefixes(
        bucketName, objectNamePrefix, delimiter, listedObjects, listedPrefixes);

    // Just use the prefix list as a starting point, and extract all the names from the
    // StorageObjects, adding them to the list.
    // TODO(user): Maybe de-dupe if it's possible for GCS to return duplicates.
    List<String> objectNames = listedPrefixes;
    for (StorageObject obj : listedObjects) {
      objectNames.add(obj.getName());
    }
    return objectNames;
  }

  /**
   * See {@link GoogleCloudStorage#listObjectInfo(String, String, String)} for details about
   * expected behavior.
   */
  @Override
  public List<GoogleCloudStorageItemInfo> listObjectInfo(
      final String bucketName, String objectNamePrefix, String delimiter)
      throws IOException {
    log.debug("listObjectInfo(%s, %s, %s)", bucketName, objectNamePrefix, delimiter);

    // Helper will handle going through pages of list results and accumulating them.
    List<StorageObject> listedObjects = new ArrayList<>();
    List<String> listedPrefixes = new ArrayList<>();
    listStorageObjectsAndPrefixes(
        bucketName, objectNamePrefix, delimiter, listedObjects, listedPrefixes);

    // For the listedObjects, we simply parse each item into a GoogleCloudStorageItemInfo without
    // further work.
    List<GoogleCloudStorageItemInfo> objectInfos = new ArrayList<>();
    for (StorageObject obj : listedObjects) {
      objectInfos.add(createItemInfoForStorageObject(
          new StorageResourceId(bucketName, obj.getName()), obj));
    }

    if (listedPrefixes.size() > 0) {
      // Send requests to fetch info about the directories associated with each prefix in batch
      // requests, maxRequestsPerBatch at a time.
      List<StorageResourceId> resourceIdsForPrefixes = new ArrayList<>();
      for (String prefix : listedPrefixes) {
        resourceIdsForPrefixes.add(new StorageResourceId(bucketName, prefix));
      }
      List<GoogleCloudStorageItemInfo> prefixInfos = getItemInfos(resourceIdsForPrefixes);
      List<StorageResourceId> repairList = new ArrayList<>();
      for (GoogleCloudStorageItemInfo prefixInfo : prefixInfos) {
        if (prefixInfo.exists()) {
          objectInfos.add(prefixInfo);
        } else {
          // This indicates a likely "implicit directory" due to a StorageObject lacking a GHFS-
          // created parent directory.
          String errorBase = String.format(
              "Error retrieving object for a retrieved prefix with resourceId '%s'. ",
              prefixInfo.getResourceId());
          if (storageOptions.isAutoRepairImplicitDirectoriesEnabled()) {
            log.debug(errorBase + "Attempting to repair missing directory.");
            repairList.add(prefixInfo.getResourceId());
          } else {
            log.error(errorBase + "Giving up on retrieving missing directory.");
          }
        }
      }

      // Handle repairs.
      if (storageOptions.isAutoRepairImplicitDirectoriesEnabled() && !repairList.isEmpty()) {
        try {
          log.warn("Repairing batch of %d missing directories.", repairList.size());
          if (repairList.size() == 1) {
            createEmptyObject(repairList.get(0));
          } else {
            createEmptyObjects(repairList);
          }

          // Fetch and append all the repaired metadatas.
          List<GoogleCloudStorageItemInfo> repairedInfos = getItemInfos(repairList);
          int numRepaired = 0;
          for (GoogleCloudStorageItemInfo repairedInfo : repairedInfos) {
            if (repairedInfo.exists()) {
              objectInfos.add(repairedInfo);
              ++numRepaired;
            } else {
              log.warn("Somehow the repair for '%s' failed quietly", repairedInfo.getResourceId());
            }
          }
          log.warn("Successfully repaired %d/%d implicit directories.",
              numRepaired, repairList.size());
        } catch (IOException ioe) {
          // Don't totally fail the listObjectInfo call, since auto-repair is best-effort
          // anyways.
          log.error("Failed to repair some missing directories.", ioe);
        }
      }
    }
    return objectInfos;
  }

  /**
   * Helper for converting a StorageResourceId + Bucket into a GoogleCloudStorageItemInfo.
   */
  @VisibleForTesting
  static GoogleCloudStorageItemInfo createItemInfoForBucket(
      StorageResourceId resourceId, Bucket bucket) {
    Preconditions.checkArgument(resourceId != null, "resourceId must not be null");
    Preconditions.checkArgument(bucket != null, "bucket must not be null");
    Preconditions.checkArgument(resourceId.isBucket(),
        String.format("resourceId must be a Bucket. resourceId: %s", resourceId));
    Preconditions.checkArgument(resourceId.getBucketName().equals(bucket.getName()),
        String.format("resourceId.getBucketName() must equal bucket.getName(): '%s' vs '%s'",
            resourceId.getBucketName(), bucket.getName()));

    // For buckets, size is 0.
    return new GoogleCloudStorageItemInfo(resourceId, bucket.getTimeCreated().getValue(),
        0, bucket.getLocation(), bucket.getStorageClass());
  }

  /**
   * Helper for converting a StorageResourceId + StorageObject into a GoogleCloudStorageItemInfo.
   */
  @VisibleForTesting
  static GoogleCloudStorageItemInfo createItemInfoForStorageObject(
      StorageResourceId resourceId, StorageObject object) {
    Preconditions.checkArgument(resourceId != null, "resourceId must not be null");
    Preconditions.checkArgument(object != null, "object must not be null");
    Preconditions.checkArgument(resourceId.isStorageObject(),
        String.format("resourceId must be a StorageObject. resourceId: %s", resourceId));
    Preconditions.checkArgument(resourceId.getBucketName().equals(object.getBucket()),
        String.format("resourceId.getBucketName() must equal object.getBucket(): '%s' vs '%s'",
            resourceId.getBucketName(), object.getBucket()));
    Preconditions.checkArgument(resourceId.getObjectName().equals(object.getName()),
        String.format("resourceId.getObjectName() must equal object.getName(): '%s' vs '%s'",
            resourceId.getObjectName(), object.getName()));

    Map<String, byte[]> decodedMetadata =
        object.getMetadata() == null ? null
            : Maps.transformValues(object.getMetadata(), DECODE_METADATA_VALUES);

    // GCS API does not make available location and storage class at object level at present
    // (it is same for all objects in a bucket). Further, we do not use the values for objects.
    // The GoogleCloudStorageItemInfo thus has 'null' for location and storage class.
    return new GoogleCloudStorageItemInfo(
        resourceId,
        object.getUpdated().getValue(),
        object.getSize().longValue(),
        null,
        null,
        decodedMetadata,
        object.getGeneration(),
        object.getMetageneration());
  }

  /**
   * Helper for creating a "not found" GoogleCloudStorageItemInfo for a StorageResourceId.
   */
  @VisibleForTesting
  static GoogleCloudStorageItemInfo createItemInfoForNotFound(StorageResourceId resourceId) {
    Preconditions.checkArgument(resourceId != null, "resourceId must not be null");

    // Return size == -1, creationTime == 0, location == storageClass == null for a not-found
    // Bucket or StorageObject.
    return new GoogleCloudStorageItemInfo(resourceId, 0, -1, null, null);
  }

  /**
   * See {@link GoogleCloudStorage#getItemInfos(List<StorageResourceId>)} for details about expected
   * behavior.
   */
  @Override
  public List<GoogleCloudStorageItemInfo> getItemInfos(List<StorageResourceId> resourceIds)
      throws IOException {
    log.debug("getItemInfos(%s)", resourceIds.toString());

    final Map<StorageResourceId, GoogleCloudStorageItemInfo> itemInfos = new HashMap<>();
    final List<IOException> innerExceptions = new ArrayList<>();
    BatchHelper batchHelper = batchFactory.newBatchHelper(
        httpRequestInitializer,
        gcs,
        storageOptions.getMaxRequestsPerBatch());

    // For each resourceId, we'll either directly add ROOT_INFO, enqueue a Bucket fetch request, or
    // enqueue a StorageObject fetch request.
    for (final StorageResourceId resourceId : resourceIds) {
      if (resourceId.isRoot()) {
        itemInfos.put(resourceId, GoogleCloudStorageItemInfo.ROOT_INFO);
      } else if (resourceId.isBucket()) {
        batchHelper.queue(
            gcs.buckets().get(resourceId.getBucketName()), new JsonBatchCallback<Bucket>() {
          @Override
          public void onSuccess(Bucket bucket, HttpHeaders responseHeaders) {
            log.debug("getItemInfos: Successfully fetched bucket: %s for resourceId: %s",
                bucket, resourceId);
            itemInfos.put(resourceId, createItemInfoForBucket(resourceId, bucket));
          }

          @Override
          public void onFailure(GoogleJsonError e, HttpHeaders responseHeaders) {
            if (errorExtractor.itemNotFound(e)) {
              log.debug("getItemInfos: bucket not found: %s", resourceId.getBucketName());
              itemInfos.put(resourceId, createItemInfoForNotFound(resourceId));
            } else {
              innerExceptions.add(wrapException(
                  new IOException(e.toString()), "Error getting Bucket: ",
                  resourceId.getBucketName(), null));
            }
          }
        });
      } else {
        final String bucketName = resourceId.getBucketName();
        final String objectName = resourceId.getObjectName();
        batchHelper.queue(
            gcs.objects().get(bucketName, objectName), new JsonBatchCallback<StorageObject>() {
          @Override
          public void onSuccess(StorageObject obj, HttpHeaders responseHeaders) {
            log.debug("getItemInfos: Successfully fetched object '%s' for resourceId '%s'",
                obj, resourceId);
            itemInfos.put(resourceId, createItemInfoForStorageObject(resourceId, obj));
          }

          @Override
          public void onFailure(GoogleJsonError e, HttpHeaders responseHeaders) {
            if (errorExtractor.itemNotFound(e)) {
              log.debug("getItemInfos: object not found: %s", resourceId);
              itemInfos.put(resourceId, createItemInfoForNotFound(resourceId));
            } else {
              innerExceptions.add(wrapException(
                  new IOException(e.toString()), "Error getting StorageObject: ",
                  bucketName, objectName));
            }
          }
        });
      }
    }
    batchHelper.flush();

    if (innerExceptions.size() > 0) {
      throw GoogleCloudStorageExceptions.createCompositeException(innerExceptions);
    }

    // Assemble the return list in the same order as the input arguments.
    List<GoogleCloudStorageItemInfo> sortedItemInfos = new ArrayList<>();
    for (StorageResourceId resourceId : resourceIds) {
      Preconditions.checkState(itemInfos.containsKey(resourceId),
          String.format("Somehow missing resourceId '%s' from map: %s", resourceId, itemInfos));
      sortedItemInfos.add(itemInfos.get(resourceId));
    }

    // We expect the return list to be the same size, even if some entries were "not found".
    Preconditions.checkState(sortedItemInfos.size() == resourceIds.size(), String.format(
        "sortedItemInfos.size() (%d) != resourceIds.size() (%d). infos: %s, ids: %s",
        sortedItemInfos.size(), resourceIds.size(),  sortedItemInfos, resourceIds));
    return sortedItemInfos;
  }

  @Override
  public List<GoogleCloudStorageItemInfo> updateItems(List<UpdatableItemInfo> itemInfoList)
      throws IOException {
    log.debug("updateItems(%s)", itemInfoList.toString());

    final Map<StorageResourceId, GoogleCloudStorageItemInfo> resultItemInfos = new HashMap<>();
    final List<IOException> innerExceptions = new ArrayList<>();
    BatchHelper batchHelper = batchFactory.newBatchHelper(
        httpRequestInitializer,
        gcs,
        storageOptions.getMaxRequestsPerBatch());

    for (UpdatableItemInfo itemInfo : itemInfoList) {
      Preconditions.checkArgument(!itemInfo.getStorageResourceId().isBucket()
          && !itemInfo.getStorageResourceId().isRoot(),
          "Buckets and GCS Root resources are not supported for updateItems");
    }

    for (final UpdatableItemInfo itemInfo : itemInfoList) {
      final StorageResourceId resourceId = itemInfo.getStorageResourceId();
      final String bucketName = resourceId.getBucketName();
      final String objectName = resourceId.getObjectName();

      Map<String, byte[]> originalMetadata = itemInfo.getMetadata();
      Map<String, String> rewrittenMetadata =
          Maps.transformValues(originalMetadata, ENCODE_METADATA_VALUES);

      Storage.Objects.Patch patch =
          gcs.objects().patch(
              bucketName,
              objectName,
              new StorageObject().setMetadata(rewrittenMetadata));

      batchHelper.queue(patch, new JsonBatchCallback<StorageObject>() {
        @Override
        public void onSuccess(StorageObject obj, HttpHeaders responseHeaders) {
          log.debug("updateItems: Successfully updated object '%s' for resourceId '%s'",
              obj, resourceId);
          resultItemInfos.put(resourceId, createItemInfoForStorageObject(resourceId, obj));
        }

        @Override
        public void onFailure(GoogleJsonError e, HttpHeaders responseHeaders) {
          if (errorExtractor.itemNotFound(e)) {
            log.debug("updateItems: object not found: %s", resourceId);
            resultItemInfos.put(resourceId, createItemInfoForNotFound(resourceId));
          } else {
            innerExceptions.add(wrapException(
                new IOException(e.toString()), "Error getting StorageObject: ",
                bucketName, objectName));
          }
        }
      });
    }
    batchHelper.flush();

    if (innerExceptions.size() > 0) {
      throw GoogleCloudStorageExceptions.createCompositeException(innerExceptions);
    }

    // Assemble the return list in the same order as the input arguments.
    List<GoogleCloudStorageItemInfo> sortedItemInfos = new ArrayList<>();
    for (UpdatableItemInfo itemInfo : itemInfoList) {
      Preconditions.checkState(resultItemInfos.containsKey(itemInfo.getStorageResourceId()),
          String.format(
              "Missing resourceId '%s' from map: %s",
              itemInfo.getStorageResourceId(),
              resultItemInfos));
      sortedItemInfos.add(resultItemInfos.get(itemInfo.getStorageResourceId()));
    }

    // We expect the return list to be the same size, even if some entries were "not found".
    Preconditions.checkState(sortedItemInfos.size() == itemInfoList.size(), String.format(
        "sortedItemInfos.size() (%d) != resourceIds.size() (%d). infos: %s, updateItemInfos: %s",
        sortedItemInfos.size(), itemInfoList.size(), sortedItemInfos, itemInfoList));
    return sortedItemInfos;
  }

  /**
   * See {@link GoogleCloudStorage#getItemInfo(StorageResourceId)} for details about expected
   * behavior.
   */
  @Override
  public GoogleCloudStorageItemInfo getItemInfo(StorageResourceId resourceId)
      throws IOException {
    log.debug("getItemInfo(%s)", resourceId);

    // Handle ROOT case first.
    if (resourceId.isRoot()) {
      return GoogleCloudStorageItemInfo.ROOT_INFO;
    }

    GoogleCloudStorageItemInfo itemInfo = null;

    // Determine object size.
    //
    // For buckets, size is 0.
    // For objects not found, size is -1.
    // For objects that exist, size is in number of bytes.
    if (resourceId.isBucket()) {
      Bucket bucket = getBucket(resourceId.getBucketName());
      if (bucket != null) {
        itemInfo = createItemInfoForBucket(resourceId, bucket);
      }
    } else {
      StorageObject object = getObject(resourceId);
      if (object != null) {
        itemInfo = createItemInfoForStorageObject(resourceId, object);
      }
    }

    if (itemInfo == null) {
      itemInfo = createItemInfoForNotFound(resourceId);
    }
    log.debug("getItemInfo: %s", itemInfo);
    return itemInfo;
  }

  /**
   * See {@link GoogleCloudStorage#close()} for details about expected behavior.
   */
  @Override
  public void close() {
    // Calling shutdown() is a no-op if it was already called earlier,
    // therefore no need to guard against that by setting threadPool to null.
    log.debug("close()");
    threadPool.shutdown();
    manualBatchingThreadPool.shutdown();
  }

  /**
   * Gets the bucket with the given name.
   *
   * @param bucketName name of the bucket to get
   * @return the bucket with the given name or null if bucket not found
   * @throws IOException if the bucket exists but cannot be accessed
   */
  private Bucket getBucket(String bucketName)
      throws IOException {
    log.debug("getBucket(%s)", bucketName);
    Preconditions.checkArgument(!Strings.isNullOrEmpty(bucketName),
        "bucketName must not be null or empty");
    Bucket bucket = null;
    Storage.Buckets.Get getBucket = gcs.buckets().get(bucketName);
    try {
      bucket = getBucket.execute();
    } catch (IOException e) {
      if (errorExtractor.itemNotFound(e)) {
        log.debug("getBucket(%s) : not found", bucketName);
      } else {
        log.debug(String.format("getBucket(%s) threw exception: ", bucketName), e);
        throw wrapException(e, "Error accessing", bucketName, null);
      }
    }
    return bucket;
  }

  /**
   * Wraps the given IOException into another IOException,
   * adding the given error message and a reference to the supplied
   * bucket and object. It allows one to know which bucket and object
   * were being accessed when the exception occurred for an operation.
   */
  @VisibleForTesting
  IOException wrapException(IOException e, String message,
      String bucketName, String objectName) {
    String name = "bucket: " + bucketName;
    if (!Strings.isNullOrEmpty(objectName)) {
      name += ", object: " + objectName;
    }
    String fullMessage = String.format("%s: %s", message, name);
    return new IOException(fullMessage, e);
  }

  /**
   * Gets the object with the given resourceId.
   *
   * @param resourceId identifies a StorageObject
   * @return the object with the given name or null if object not found
   * @throws IOException if the object exists but cannot be accessed
   */
  private StorageObject getObject(StorageResourceId resourceId)
      throws IOException {
    log.debug("getObject(%s)", resourceId);
    Preconditions.checkArgument(resourceId.isStorageObject(),
        "Expected full StorageObject id, got " + resourceId);
    String bucketName = resourceId.getBucketName();
    String objectName = resourceId.getObjectName();
    StorageObject object = null;
    Storage.Objects.Get getObject = gcs.objects().get(bucketName, objectName);
    try {
      object = getObject.execute();
    } catch (IOException e) {
      if (errorExtractor.itemNotFound(e)) {
        log.debug("getObject(%s) : not found", resourceId);
      } else {
        log.debug(String.format("getObject(%s) threw exception: ", resourceId), e);
        throw wrapException(e, "Error accessing", bucketName, objectName);
      }
    }
    return object;
  }

  /**
   * See {@link GoogleCloudStorage#waitForBucketEmpty(String)} for details about expected behavior.
   */
  @Override
  public void waitForBucketEmpty(String bucketName)
      throws IOException {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(bucketName),
        "bucketName must not be null or empty");

    int maxRetries = BUCKET_EMPTY_MAX_RETRIES;
    int waitTime = BUCKET_EMPTY_WAIT_TIME_MS;  // milliseconds
    for (int i = 0; i < maxRetries; i++) {
      List<String> objectNames = listObjectNames(bucketName, null, PATH_DELIMITER);
      if (objectNames.size() == 0) {
        return;
      }
      try {
        sleeper.sleep(waitTime);
      } catch (InterruptedException ignored) {
        // Ignore the exception and loop.
      }
    }
    throw new IOException("Internal error: bucket not empty: " + bucketName);
  }
}
