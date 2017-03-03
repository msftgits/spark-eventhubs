/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.streaming.eventhubs

import java.util.concurrent.atomic.AtomicInteger

import org.apache.spark.eventhubscommon.{EventHubNameAndPartition, EventHubsConnector, RateControlUtils}
import org.apache.spark.eventhubscommon.client.{EventHubClient, EventHubsClientWrapper, RestfulEventHubClient}
import org.apache.spark.eventhubscommon.progress.ProgressTrackerBase
import org.apache.spark.eventhubscommon.rdd.{EventHubsRDD, OffsetRange, OffsetStoreParams}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.{DataFrame, SQLContext}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.execution.streaming.{Offset, SerializedOffset, Source}
import org.apache.spark.sql.streaming.eventhubs.checkpoint.StructuredStreamingProgressTracker
import org.apache.spark.sql.types._

/**
 * EventHubSource connecting each EventHubs instance to a Structured Streaming Source
 * @param parameters the eventhubs parameters
 */
private[spark] class EventHubsSource(
    sqlContext: SQLContext,
    parameters: Map[String, String],
    eventhubReceiverCreator: (Map[String, String], Int, Long, Int) => EventHubsClientWrapper =
      EventHubsClientWrapper.getEventHubReceiver,
    eventhubClientCreator: (String, Map[String, Map[String, String]]) => EventHubClient =
      RestfulEventHubClient.getInstance) extends Source with EventHubsConnector with Logging {

  case class EventHubsOffset(batchId: Long, offsets: Map[EventHubNameAndPartition, (Long, Long)])

  private val eventhubsNamespace: String = parameters("eventhubs.namespace")
  private val eventhubsName: String = parameters("eventhubs.name")

  require(eventhubsNamespace != null, "eventhubs.namespace is not defined")
  require(eventhubsName != null, "eventhubs.name is not defined")

  private var _eventHubClient: EventHubClient = _

  private[eventhubs] def eventHubClient = {
    if (_eventHubClient == null) {
      _eventHubClient = eventhubClientCreator(eventhubsNamespace, Map(eventhubsName -> parameters))
    }
    _eventHubClient
  }

  private val ehNameAndPartitions = {
    val partitionCount = parameters("eventhubs.partition.count").toInt
    (for (partitionId <- 0 until partitionCount)
      yield EventHubNameAndPartition(eventhubsName, partitionId)).toList
  }

  // EventHubsSource is created for each instance of program, that means it is different with
  // DStream which will load the serialized Direct DStream instance from checkpoint
  StructuredStreamingProgressTracker.registeredConnectors += uid -> this

  // initialize ProgressTracker
  private val progressTracker = StructuredStreamingProgressTracker.initInstance(
    uid, parameters("eventhubs.progressTrackingDir"), sqlContext.sparkContext.appName,
    sqlContext.sparkContext.hadoopConfiguration)

  private[eventhubs] def setEventHubClient(eventHubClient: EventHubClient): EventHubsSource = {
    _eventHubClient = eventHubClient
    this
  }

  // the flag to avoid committing in the first batch
  private var firstBatch = true
  // the offsets which have been to the self-managed offset store
  private var committedOffsetsAndSeqNums: EventHubsOffset =
    EventHubsOffset(-1L, ehNameAndPartitions.map((_, (-1L, -1L))).toMap)
  // the highest offsets in EventHubs side
  private var fetchedHighestOffsetsAndSeqNums: EventHubsOffset = _

  override def schema: StructType = {
    val userDefinedKeys = parameters.get("eventhubs.sql.userDefinedKeys") match {
      case Some(keys) =>
        keys.split(",").toSeq
      case None =>
        Seq()
    }
    EventHubsSourceProvider.sourceSchema(userDefinedKeys)
  }

  private[spark] def composeHighestOffset(retryIfFail: Boolean) = {
    RateControlUtils.fetchLatestOffset(eventHubClient, retryIfFail = retryIfFail) match {
      case Some(highestOffsets) =>
        fetchedHighestOffsetsAndSeqNums = EventHubsOffset(committedOffsetsAndSeqNums.batchId,
          highestOffsets)
        Some(fetchedHighestOffsetsAndSeqNums.offsets)
      case _ =>
        logWarning(s"failed to fetch highest offset")
        if (retryIfFail) {
          None
        } else {
          Some(fetchedHighestOffsetsAndSeqNums.offsets)
        }
    }
  }

  /**
   * when we have reached the end of the message queue in the remote end or we haven't get any
   * idea about the highest offset, we shall fail the app when rest endpoint is not responsive, and
   * to prevent us from dying too much, we shall retry with 2-power interval in this case
   */
  private def failAppIfRestEndpointFail = fetchedHighestOffsetsAndSeqNums == null ||
    committedOffsetsAndSeqNums.offsets.equals(fetchedHighestOffsetsAndSeqNums.offsets)

  /**
   * there are two things to do in this function, first is to collect the ending offsets of last
   * batch, so that we know the starting offset of the current batch. And then, we calculate the
   * target seq number of the current batch
   * @return return the target seqNum of current batch
   */
  override def getOffset: Option[Offset] = {
    val highestOffsetsOpt = composeHighestOffset(failAppIfRestEndpointFail)
    require(highestOffsetsOpt.isDefined, "cannot get highest offset from rest endpoint of" +
      " eventhubs")
    if (!firstBatch) {
      // committedOffsetsAndSeqNums.batchId is always no larger than the latest finished batch id
      collectFinishedBatchOffsetsAndCommit(committedOffsetsAndSeqNums.batchId + 1)
    } else {
      firstBatch = false
    }
    val targetOffsets = RateControlUtils.clamp(committedOffsetsAndSeqNums.offsets,
      highestOffsetsOpt.get, parameters)
    Some(EventHubsBatchRecord(committedOffsetsAndSeqNums.batchId + 1,
      targetOffsets.map{case (ehNameAndPartition, seqNum) =>
        (ehNameAndPartition, math.min(seqNum,
          fetchedHighestOffsetsAndSeqNums.offsets(ehNameAndPartition)._2))}))
  }

  /**
   * collect the ending offsets/seq from executors to driver and commit
   */
  private def collectFinishedBatchOffsetsAndCommit(committedBatchId: Long): Unit = {
    committedOffsetsAndSeqNums = fetchEndingOffsetOfLastBatch(committedBatchId)
    // we have two ways to handle the failure of commit and precommit:
    // First, we will validate the progress file and overwrite the corrupted progress file when
    // progressTracker is created; Second, to handle the case that we fail before we event create
    // a file, we need to read the latest progress file in the directory and see if we have commit
    // the offsests (check if the timestamp matches) and then collect the files if necessary
    progressTracker.commit(Map(uid -> committedOffsetsAndSeqNums.offsets), committedBatchId)
    logInfo(s"committed offsets of batch $committedBatchId, collectedCommits:" +
      s" $committedOffsetsAndSeqNums")
  }

  private def fetchEndingOffsetOfLastBatch(committedBatchId: Long) = {
    val startOffsetOfUndergoingBatch = progressTracker.collectProgressRecordsForBatch(
      committedBatchId)
    if (startOffsetOfUndergoingBatch.isEmpty) {
      // first batch, take the initial value of the offset, -1
      EventHubsOffset(committedBatchId, committedOffsetsAndSeqNums.offsets)
    } else {
      EventHubsOffset(committedBatchId,
        startOffsetOfUndergoingBatch.filter { case (connectorUID, _) =>
          connectorUID == uid
        }.values.head.filter(_._1.eventHubName == parameters("eventhubs.name")))
    }
  }

  private def buildEventHubsRDD(endOffset: EventHubsBatchRecord): EventHubsRDD = {
    val offsetRanges = fetchedHighestOffsetsAndSeqNums.offsets.map {
      case (eventHubNameAndPartition, (_, endSeqNum)) =>
        OffsetRange(eventHubNameAndPartition,
          fromOffset = committedOffsetsAndSeqNums.offsets(eventHubNameAndPartition)._1,
          fromSeq = committedOffsetsAndSeqNums.offsets(eventHubNameAndPartition)._2,
          untilSeq = endOffset.targetSeqNums(eventHubNameAndPartition))
    }.toList
    new EventHubsRDD(
      sqlContext.sparkContext,
      Map(parameters("eventhubs.name") -> parameters),
      offsetRanges,
      committedOffsetsAndSeqNums.batchId + 1,
      OffsetStoreParams(parameters("eventhubs.progressTrackingDir"),
        streamId, uid = uid, subDirs = sqlContext.sparkContext.appName, uid),
      eventhubReceiverCreator
    )
  }

  private def convertEventHubsRDDToDataFrame(eventHubsRDD: EventHubsRDD): DataFrame = {
    import scala.collection.JavaConverters._
    val internalRowRDD = eventHubsRDD.map(eventData =>
      InternalRow.fromSeq(Seq(eventData.getBody, eventData.getSystemProperties.getOffset.toLong,
        eventData.getSystemProperties.getSequenceNumber,
        eventData.getSystemProperties.getEnqueuedTime.getEpochSecond,
        eventData.getSystemProperties.getPublisher,
        eventData.getSystemProperties.getPartitionKey
      ) ++ eventData.getProperties.asScala.values)
    )
    sqlContext.internalCreateDataFrame(internalRowRDD, schema)
  }

  private def readProgress(batchId: Long): EventHubsOffset = {
    val progress = progressTracker.read(uid, batchId, fallBack = false)
    EventHubsOffset(batchId, progress.offsets)
  }

  private def recoverFromFailure(start: Option[Offset], end: Offset): Unit = {
    val recoveredCommittedBatchId = {
      if (start.isEmpty) {
        -1
      } else {
        start.map {
          case so: SerializedOffset =>
            val batchRecord = JsonUtils.partitionAndSeqNum(so.json)
            batchRecord.asInstanceOf[EventHubsBatchRecord].batchId
          case batchRecord: EventHubsBatchRecord =>
            batchRecord.batchId
        }.get
      }
    }
    val latestProgress = readProgress(recoveredCommittedBatchId)
    if (latestProgress.offsets.isEmpty && start.isDefined) {
      // we shall not commit when start is empty, otherwise, we will have a duplicate processing
      // of the first batch
      collectFinishedBatchOffsetsAndCommit(recoveredCommittedBatchId)
    } else {
      committedOffsetsAndSeqNums = latestProgress
    }
    logInfo(s"recovered from a failure, startOffset: $start, endOffset: $end")
    val highestOffsets = composeHighestOffset(failAppIfRestEndpointFail)
    require(highestOffsets.isDefined, "cannot get highest offsets when recovering from a failure")
    fetchedHighestOffsetsAndSeqNums = EventHubsOffset(committedOffsetsAndSeqNums.batchId,
      highestOffsets.get)
    firstBatch = false
  }

  override def getBatch(start: Option[Offset], end: Offset): DataFrame = {
    if (firstBatch) {
      // in this case, we are just recovering from a failure; the committedOffsets and
      // availableOffsets are fetched from in populateStartOffset() of StreamExecution
      // convert (committedOffsetsAndSeqNums is in initial state)
      recoverFromFailure(start, end)
    }
    val eventhubsRDD = buildEventHubsRDD({
      end match {
        case so: SerializedOffset =>
          JsonUtils.partitionAndSeqNum(so.json)
        case batchRecord: EventHubsBatchRecord =>
          batchRecord
      }
    })
    convertEventHubsRDDToDataFrame(eventhubsRDD)
  }

  override def stop(): Unit = {}

  // uniquely identify the entities in eventhubs side, it can be the namespace or the name of a
  override def uid: String = s"${eventhubsNamespace}_$eventhubsName"

  // the list of eventhubs partitions connecting with this connector
  override def connectedInstances: List[EventHubNameAndPartition] = ehNameAndPartitions

  // the id of the stream which is mapped from eventhubs instance
  override val streamId: Int = EventHubsSource.streamIdGenerator.getAndIncrement()
}

private object EventHubsSource {
  val streamIdGenerator = new AtomicInteger(0)
}