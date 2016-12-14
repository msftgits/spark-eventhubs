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

package org.apache.spark.streaming.eventhubs

import scala.collection.mutable

import com.microsoft.azure.eventhubs.{EventData, PartitionReceiver}

import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.{StreamingContext, Time}
import org.apache.spark.streaming.dstream.{DStream, DStreamCheckpointData, InputDStream}
import org.apache.spark.streaming.eventhubs.checkpoint._
import org.apache.spark.streaming.scheduler.{RateController, StreamInputInfo}
import org.apache.spark.streaming.scheduler.rate.RateEstimator

/**
 * implementation of EventHub-based direct stream
 * @param _ssc the streaming context this stream belongs to
 * @param eventHubNameSpace the namespace of evenhub instances
 * @param checkpointDir the checkpoint directory path (we only support HDFS-based checkpoint
 *                      storage for now, so you have to prefix your path with hdfs://clustername/
 * @param eventhubsParams the parameters of your eventhub instances, format:
 *                    Map[eventhubinstanceName -> Map(parameterName -> parameterValue)
 */
private[eventhubs] class EventHubDirectDStream(
    _ssc: StreamingContext,
    eventHubNameSpace: String,
    checkpointDir: String,
    eventhubsParams: Map[String, Map[String, String]],
    eventhubClientCreator: (Map[String, String], Int, Long, Int) => EventHubsClientWrapper =
                                              EventHubsClientWrapper.getEventHubClient)
  extends InputDStream[EventData](_ssc) with Logging {

  private[streaming] override def name: String = s"EventHub direct stream [$id]"

  protected[streaming] override val checkpointData = new EventHubDirectDStreamCheckpointData

  override protected[streaming] val rateController: Option[RateController] = {
    if (RateController.isBackPressureEnabled(ssc.sparkContext.conf)) {
      Some(new EventHubDirectDStreamRateController(id, RateEstimator.create(ssc.sparkContext.conf,
        graph.batchDuration)))
    } else {
      None
    }
  }

  private def maxRateLimitPerPartition(eventHubName: String): Int = {
    val x = eventhubsParams(eventHubName).getOrElse("eventhubs.maxRate", "10000").toInt
    require(x > 0, s"eventhubs.maxRate has to be larger than zero, violated by $eventHubName ($x)")
    x
  }

  @transient private var _eventHubClient: EventHubClient = _
  @transient private var _offsetStore: OffsetStoreDirectStreaming = _

  private[eventhubs] def setEventHubClient(eventHubClient: EventHubClient): Unit = {
    _eventHubClient = eventHubClient
  }

  private[eventhubs] def eventHubClient = {
    if (_eventHubClient == null) {
      // TODO: enable customized implementation in future
      _eventHubClient = new RestfulEventHubClient(eventHubNameSpace,
        numPartitionsEventHubs = {
          eventhubsParams.map { case (eventhubName, params) => (eventhubName,
            params("eventhubs.partition.count").toInt)
          }
        },
        consumerGroups = {
          eventhubsParams.map { case (eventhubName, params) => (eventhubName,
            params("eventhubs.consumergroup"))
          }
        },
        policyKeys = eventhubsParams.map { case (eventhubName, params) => (eventhubName,
          (params("eventhubs.policyname"), params("eventhubs.policykey")))
        },
        threadNum = 15)
    }
    _eventHubClient
  }

  private[eventhubs] def setOffsetStore(offsetStore: OffsetStoreDirectStreaming): Unit = {
    _offsetStore = offsetStore
  }

  // Only for test
  private[eventhubs] def offsetStore = {
    if (_offsetStore == null) {
      // TODO: enable customized implementation in future
      _offsetStore = OffsetStoreDirectStreaming.newInstance(
        checkpointDir,
        ssc.sparkContext.appName,
        this.id,
        eventHubNameSpace,
        ssc.sparkContext.hadoopConfiguration)
    }
    _offsetStore
  }

  // from eventHubName to offset
  private[eventhubs] var currentOffsetsAndSeqNums: Map[EventHubNameAndPartition, (Long, Long)] =
    Map[EventHubNameAndPartition, (Long, Long)]()

  override def start(): Unit = {
    val concurrentJobs = ssc.conf.getInt("spark.streaming.concurrentJobs", 1)
    require(concurrentJobs == 1,
      "due to the limitation from eventhub, we do not allow to have multiple concurrent spark jobs")
    ssc.addStreamingListener(new CheckpointListener)
  }

  override def stop(): Unit = {
    eventHubClient.close()
    offsetStore.close()
  }

  private def fetchLatestOffset(validTime: Time): Map[EventHubNameAndPartition, (Long, Long)] = {
    // TODO: rate control
    val latestOffsets = eventHubClient.endPointOfPartition()
    if (latestOffsets.isDefined) {
      latestOffsets.get
    } else {
      logWarning(s"cannot get latest offset at time $validTime")
      Map()
    }
  }

  /**
   * EventHub uses *Number Of Messages* for rate control, but uses *offset* to setup of the start
   * point of the receivers. As a result, we need to translate the sequence number to offset to
   * start receiver in the next batch, which is a functionality not provided by EventHub. We use
   * checkpoint file to communicate this information between executors and driver.
   *
   * In this function, we either read startpoint from checkpoint file or start processing from
   * the very beginning of the streams
   *
   * @return EventHubName-Partition -> (offset, seq)
   */
  private def fetchStartOffsetForEachPartition(validTime: Time):
    Map[EventHubNameAndPartition, (Long, Long)] = {
    val checkpoints = offsetStore.read()
    if (checkpoints.nonEmpty) {
      logInfo(s"get checkpoint at $validTime: $checkpoints")
      checkpoints
    } else {
      // start from the beginning of each eventHubPartition
      {
        for (eventHubName <- eventhubsParams.keys;
             p <- 0 until eventhubsParams(eventHubName)("eventhubs.partition.count").toInt)
          yield (EventHubNameAndPartition(eventHubName, p),
            (PartitionReceiver.START_OF_STREAM.toLong, 0L))
      }.toMap
    }
  }

  private def canStartNextBatch: Boolean = {
    offsetStore match {
      case dfsOffsetStore: DfsBasedOffsetStore2 =>
        val fs = dfsOffsetStore.checkpointTempDirPath.getFileSystem(
          ssc.sparkContext.hadoopConfiguration)
        if (fs.exists(dfsOffsetStore.checkpointTempDirPath) &&
          fs.listStatus(dfsOffsetStore.checkpointTempDirPath).length > 0) {
          logInfo(s"checkpoint hasn't been cleaned up" +
            s" ${fs.listFiles(dfsOffsetStore.checkpointTempDirPath, false).next().toString}")
          false
        } else {
          true
        }
      case _ =>
        true
    }
  }

  private def reportInputInto(validTime: Time,
                              offsetRanges: List[OffsetRange], inputSize: Int): Unit = {
    require(inputSize >= 0, s"invalid inputSize ($inputSize) with offsetRanges: $offsetRanges")
    val description = offsetRanges.map { offsetRange =>
      s"topic: ${offsetRange.eventHubNameAndPartition}\t" +
        s"starting offsets: ${offsetRange.fromOffset}" +
        s"sequenceNumbers: ${offsetRange.fromSeq} to ${offsetRange.untilSeq}"
    }.mkString("\n")
    // Copy offsetRanges to immutable.List to prevent from being modified by the user
    val metadata = Map(
      "offsets" -> offsetRanges,
      StreamInputInfo.METADATA_KEY_DESCRIPTION -> description)
    val inputInfo = StreamInputInfo(id, inputSize, metadata)
    ssc.scheduler.inputInfoTracker.reportInfo(validTime, inputInfo)
  }

  private def validatePartitions(
      validTime: Time,
      calculatedPartitions: List[EventHubNameAndPartition]): Unit = {
    if (currentOffsetsAndSeqNums.nonEmpty) {
      val currentPartitions = currentOffsetsAndSeqNums.keys.toList
      val diff = currentPartitions.diff(calculatedPartitions)
      if (diff.nonEmpty) {
        logError(s"detected lost partitions $diff")
        throw new RuntimeException(s"some partitions are lost before $validTime")
      }
    }
  }

  private def defaultRateControl(latestEndpoints: Map[EventHubNameAndPartition, (Long, Long)]):
      Map[EventHubNameAndPartition, Long] = {
    latestEndpoints.map{
      case (eventHubNameAndPar, (_, latestSeq)) =>
        val maximumAllowedMessageCnt = maxRateLimitPerPartition(eventHubNameAndPar.eventHubName)
        (eventHubNameAndPar, math.min(latestSeq,
          maximumAllowedMessageCnt + currentOffsetsAndSeqNums(eventHubNameAndPar)._2 - 1))
    }
  }

  private def clamp(latestEndpoints: Map[EventHubNameAndPartition, (Long, Long)]):
      Map[EventHubNameAndPartition, Long] = {
    if (rateController.isEmpty) {
      defaultRateControl(latestEndpoints)
    } else {
      val estimateRateLimit = rateController.map(_.getLatestRate().toInt)
      estimateRateLimit.filter(_ > 0) match {
        case None =>
          defaultRateControl(latestEndpoints)
        case Some(allowedRate) =>
          val lagPerPartition = latestEndpoints.map {
            case (eventHubNameAndPartition, (_, latestSeq)) =>
              eventHubNameAndPartition -> math.max(latestSeq -
                currentOffsetsAndSeqNums(eventHubNameAndPartition)._2, 0)
          }
          val totalLag = lagPerPartition.values.sum
          lagPerPartition.map {
            case (eventHubNameAndPartition, lag) =>
              val backpressureRate = math.round(lag / totalLag.toFloat * allowedRate)
              eventHubNameAndPartition ->
                (math.min(backpressureRate,
                  maxRateLimitPerPartition(eventHubNameAndPartition.eventHubName)) +
                  currentOffsetsAndSeqNums(eventHubNameAndPartition)._2 - 1)
          }
      }
    }
  }

  override def compute(validTime: Time): Option[RDD[EventData]] = {
    // step 1, detect the highest offset of each eventhub instance
    // we need to create the driver-side & restful API based consumer in step 1
    val newOffsetsAndSeqNums = fetchStartOffsetForEachPartition(validTime)
    val sameCheckpoint = newOffsetsAndSeqNums.equals(currentOffsetsAndSeqNums)
    if (sameCheckpoint || !canStartNextBatch) {
      reportInputInto(validTime, List(), 0)
      Some(ssc.sparkContext.emptyRDD[EventData])
    } else {
      val endPoints = fetchLatestOffset(validTime)
      if (endPoints.nonEmpty) {
        validatePartitions(validTime, newOffsetsAndSeqNums.keys.toList)
        currentOffsetsAndSeqNums = newOffsetsAndSeqNums
        val clampedSeqIDs = clamp(endPoints)
        logInfo(s"starting batch at $validTime with $currentOffsetsAndSeqNums")
        val offsetRanges = endPoints.map {
          case (eventHubNameAndPartition, (_, endSeqNum)) =>
            OffsetRange(eventHubNameAndPartition,
              fromOffset = currentOffsetsAndSeqNums(eventHubNameAndPartition)._1,
              fromSeq = currentOffsetsAndSeqNums(eventHubNameAndPartition)._2,
              untilSeq = math.min(clampedSeqIDs(eventHubNameAndPartition), endSeqNum))
        }.toList
        val eventHubRDD = new EventHubRDD(
          context.sparkContext,
          eventhubsParams,
          offsetRanges,
          validTime,
          OffsetStoreParams(checkpointDir, ssc.sparkContext.appName, this.id, eventHubNameSpace),
          eventhubClientCreator)
        reportInputInto(validTime, offsetRanges,
          offsetRanges.map(ofr => ofr.untilSeq - ofr.fromSeq + 1).sum.toInt)
        Some(eventHubRDD)
      } else {
        reportInputInto(validTime, List(), 0)
        Some(ssc.sparkContext.emptyRDD[EventData])
      }
    }
  }

  private[eventhubs] class EventHubDirectDStreamCheckpointData
    extends DStreamCheckpointData(this) {

    def batchForTime: mutable.HashMap[Time,
      Array[(EventHubNameAndPartition, Long, Long, Long)]] = {
      data.asInstanceOf[mutable.HashMap[Time,
        Array[(EventHubNameAndPartition, Long, Long, Long)]]]
    }

    override def update(time: Time): Unit = {
      batchForTime.clear()
      generatedRDDs.foreach { kv =>
        val offsetRangeOfRDD = kv._2.asInstanceOf[EventHubRDD].offsetRanges.map(_.toTuple).toArray
        batchForTime += kv._1 -> offsetRangeOfRDD
      }
    }

    override def cleanup(time: Time): Unit = { }

    override def restore(): Unit = {
      batchForTime.toSeq.sortBy(_._1)(Time.ordering).foreach { case (t, b) =>
        logInfo(s"Restoring EventHub for time $t ${b.mkString("[", ", ", "]")}")
        generatedRDDs += t -> new EventHubRDD(
          context.sparkContext,
          eventhubsParams,
          b.map {case (ehNameAndPar, fromOffset, fromSeq, untilSeq) =>
            OffsetRange(ehNameAndPar, fromOffset, fromSeq, untilSeq)}.toList,
          t,
          OffsetStoreParams(checkpointDir, ssc.sparkContext.appName, id, eventHubNameSpace),
          eventhubClientCreator)
      }
    }
  }

  private[eventhubs] class EventHubDirectDStreamRateController(id: Int, estimator: RateEstimator)
    extends RateController(id, estimator) {
    override protected def publish(rate: Long): Unit = {
      // publish nothing as there is no receiver
    }
  }
}
